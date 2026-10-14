package com.ffp.feature_flag_platform.evaluation.runtime;

import com.ffp.feature_flag_platform.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * Lazily built, explicitly invalidated cache of environment snapshots.
 *
 * <p>The subtle part is the race between a slow rebuild and an invalidation: if a rebuild
 * reads the database, then a change commits and invalidates, the rebuild must not publish
 * its (now stale) snapshot. Each environment therefore has a generation counter; a rebuild
 * only installs its result if the generation is unchanged, checked atomically with the
 * write via {@link ConcurrentMap#compute}.
 *
 * <p>Concurrent misses for one environment share a single load ("single flight"): after a
 * restart or a flag change, hundreds of in-flight requests would otherwise each rebuild the
 * same snapshot and queue on the connection pool. A request only joins a load that started
 * at the current generation, so nobody who arrives after an invalidation is handed a
 * snapshot that was read before it.
 *
 * <p>Invalidation is the primary freshness mechanism. A maximum snapshot age is the safety
 * net for invalidations that never arrive (e.g. a pub/sub message lost while this instance
 * was disconnected from Redis): it bounds staleness instead of letting it last forever.
 */
@Component
public class SnapshotCache {

    private final ConcurrentMap<Long, Entry> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, AtomicLong> generations = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Load> inFlight = new ConcurrentHashMap<>();
    private final LongFunction<EnvironmentSnapshot> loader;
    private final long maxAgeNanos;
    private final LongSupplier nanoTime;
    private final Runnable onInvalidate;

    @Autowired
    public SnapshotCache(SnapshotLoader loader, AppProperties properties, MeterRegistry meters) {
        this(timed(loader, meters), properties.cluster().maxSnapshotAge(), System::nanoTime,
                Counter.builder("ffp.snapshot.invalidations")
                        .description("Environment snapshots dropped because their configuration changed")
                        .register(meters)::increment);
    }

    SnapshotCache(LongFunction<EnvironmentSnapshot> loader) {
        this(loader, Duration.ZERO, System::nanoTime);
    }

    SnapshotCache(LongFunction<EnvironmentSnapshot> loader, Duration maxAge, LongSupplier nanoTime) {
        this(loader, maxAge, nanoTime, () -> { });
    }

    private SnapshotCache(LongFunction<EnvironmentSnapshot> loader, Duration maxAge, LongSupplier nanoTime,
                          Runnable onInvalidate) {
        this.onInvalidate = onInvalidate;
        this.loader = loader;
        this.maxAgeNanos = maxAge.toNanos();
        this.nanoTime = nanoTime;
    }

    public EnvironmentSnapshot get(long environmentId) {
        Entry cached = snapshots.get(environmentId);
        if (cached != null && !isExpired(cached)) {
            return cached.snapshot();
        }
        AtomicLong generation = generation(environmentId);
        long expected = generation.get();
        Load mine = new Load(expected, new CompletableFuture<>());
        Load leader = inFlight.compute(environmentId, (id, current) ->
                current != null && current.generation() == expected ? current : mine);
        if (leader != mine) {
            return join(leader.result());
        }
        try {
            EnvironmentSnapshot loaded = load(environmentId, cached, generation, expected);
            mine.result().complete(loaded);
            return loaded;
        } catch (RuntimeException | Error e) {
            mine.result().completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(environmentId, mine);
        }
    }

    private EnvironmentSnapshot load(long environmentId, Entry cached, AtomicLong generation, long expected) {
        long loadedAt = nanoTime.getAsLong();
        EnvironmentSnapshot fresh = loader.apply(environmentId);
        // Install only if nothing was invalidated while we were loading. A concurrent reader
        // may have installed a fresh entry meanwhile; keep it unless it has also expired.
        Entry installed = snapshots.compute(environmentId, (id, current) -> {
            if (current != null && current != cached && !isExpired(current)) {
                return current;
            }
            return generation.get() == expected ? new Entry(fresh, loadedAt) : null;
        });
        return installed != null ? installed.snapshot() : fresh;
    }

    private static EnvironmentSnapshot join(CompletableFuture<EnvironmentSnapshot> load) {
        try {
            return load.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
    }

    public void invalidate(long environmentId) {
        onInvalidate.run();
        snapshots.compute(environmentId, (id, current) -> {
            generation(environmentId).incrementAndGet();
            return null;
        });
    }

    public void invalidateAll() {
        snapshots.keySet().forEach(this::invalidate);
    }

    /** Every load is a cache miss, so the load rate against the request rate gives the hit ratio. */
    private static LongFunction<EnvironmentSnapshot> timed(SnapshotLoader loader, MeterRegistry meters) {
        Timer timer = Timer.builder("ffp.snapshot.load")
                .description("Time to build an environment snapshot from the database (one per cache miss)")
                .publishPercentileHistogram()
                .register(meters);
        return id -> timer.record(() -> loader.load(id));
    }

    private boolean isExpired(Entry entry) {
        return maxAgeNanos > 0 && nanoTime.getAsLong() - entry.loadedAtNanos() >= maxAgeNanos;
    }

    private AtomicLong generation(long environmentId) {
        return generations.computeIfAbsent(environmentId, id -> new AtomicLong());
    }

    private record Entry(EnvironmentSnapshot snapshot, long loadedAtNanos) {
    }

    /** A load in progress, started when the environment was at {@code generation}. */
    private record Load(long generation, CompletableFuture<EnvironmentSnapshot> result) {
    }
}
