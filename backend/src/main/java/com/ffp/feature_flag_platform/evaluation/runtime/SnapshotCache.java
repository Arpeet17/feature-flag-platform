package com.ffp.feature_flag_platform.evaluation.runtime;

import com.ffp.feature_flag_platform.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
 * <p>Invalidation is the primary freshness mechanism. A maximum snapshot age is the safety
 * net for invalidations that never arrive (e.g. a pub/sub message lost while this instance
 * was disconnected from Redis): it bounds staleness instead of letting it last forever.
 */
@Component
public class SnapshotCache {

    private final ConcurrentMap<Long, Entry> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, AtomicLong> generations = new ConcurrentHashMap<>();
    private final LongFunction<EnvironmentSnapshot> loader;
    private final long maxAgeNanos;
    private final LongSupplier nanoTime;

    @Autowired
    public SnapshotCache(SnapshotLoader loader, AppProperties properties) {
        this(loader::load, properties.cluster().maxSnapshotAge(), System::nanoTime);
    }

    SnapshotCache(LongFunction<EnvironmentSnapshot> loader) {
        this(loader, Duration.ZERO, System::nanoTime);
    }

    SnapshotCache(LongFunction<EnvironmentSnapshot> loader, Duration maxAge, LongSupplier nanoTime) {
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

    public void invalidate(long environmentId) {
        snapshots.compute(environmentId, (id, current) -> {
            generation(environmentId).incrementAndGet();
            return null;
        });
    }

    public void invalidateAll() {
        snapshots.keySet().forEach(this::invalidate);
    }

    private boolean isExpired(Entry entry) {
        return maxAgeNanos > 0 && nanoTime.getAsLong() - entry.loadedAtNanos() >= maxAgeNanos;
    }

    private AtomicLong generation(long environmentId) {
        return generations.computeIfAbsent(environmentId, id -> new AtomicLong());
    }

    private record Entry(EnvironmentSnapshot snapshot, long loadedAtNanos) {
    }
}
