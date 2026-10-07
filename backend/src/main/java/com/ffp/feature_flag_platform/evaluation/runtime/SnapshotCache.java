package com.ffp.feature_flag_platform.evaluation.runtime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

/**
 * Lazily built, explicitly invalidated cache of environment snapshots.
 *
 * <p>The subtle part is the race between a slow rebuild and an invalidation: if a rebuild
 * reads the database, then a change commits and invalidates, the rebuild must not publish
 * its (now stale) snapshot. Each environment therefore has a generation counter; a rebuild
 * only installs its result if the generation is unchanged, checked atomically with the
 * write via {@link ConcurrentMap#compute}.
 */
@Component
public class SnapshotCache {

    private final ConcurrentMap<Long, EnvironmentSnapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, AtomicLong> generations = new ConcurrentHashMap<>();
    private final LongFunction<EnvironmentSnapshot> loader;

    @Autowired
    public SnapshotCache(SnapshotLoader loader) {
        this(loader::load);
    }

    SnapshotCache(LongFunction<EnvironmentSnapshot> loader) {
        this.loader = loader;
    }

    public EnvironmentSnapshot get(long environmentId) {
        EnvironmentSnapshot cached = snapshots.get(environmentId);
        if (cached != null) {
            return cached;
        }
        AtomicLong generation = generation(environmentId);
        long expected = generation.get();
        EnvironmentSnapshot fresh = loader.apply(environmentId);
        // Install only if nothing was invalidated while we were loading.
        EnvironmentSnapshot installed = snapshots.compute(environmentId,
                (id, current) -> current != null ? current : (generation.get() == expected ? fresh : null));
        return installed != null ? installed : fresh;
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

    private AtomicLong generation(long environmentId) {
        return generations.computeIfAbsent(environmentId, id -> new AtomicLong());
    }
}
