package com.ffp.feature_flag_platform.evaluation.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotCacheTest {

    private static EnvironmentSnapshot snapshot(long version) {
        return new EnvironmentSnapshot(1, "env", version, Map.of(), Map.of());
    }

    @Test
    void cachesUntilInvalidated() {
        AtomicInteger loads = new AtomicInteger();
        SnapshotCache cache = new SnapshotCache(id -> snapshot(loads.incrementAndGet()));

        assertThat(cache.get(1).version()).isEqualTo(1);
        assertThat(cache.get(1).version()).isEqualTo(1);
        assertThat(loads).hasValue(1);

        cache.invalidate(1);
        assertThat(cache.get(1).version()).isEqualTo(2);
    }

    @Test
    void staleLoadRacingAnInvalidationIsNotInstalled() throws Exception {
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch invalidated = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        SnapshotCache cache = new SnapshotCache(id -> {
            int n = loads.incrementAndGet();
            if (n == 1) {
                // First load reads "old" data, then a change commits before it finishes.
                loadStarted.countDown();
                await(invalidated);
            }
            return snapshot(n);
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var slowRead = executor.submit(() -> cache.get(1));
            assertThat(loadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            cache.invalidate(1);
            invalidated.countDown();

            // The racing reader gets its own (old) result, but must not poison the cache...
            assertThat(slowRead.get(5, TimeUnit.SECONDS).version()).isEqualTo(1);
        }
        // ...so the next reader loads fresh data.
        assertThat(cache.get(1).version()).isEqualTo(2);
    }

    @Test
    void maxAgeBoundsStalenessWhenAnInvalidationIsLost() {
        AtomicInteger loads = new AtomicInteger();
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        SnapshotCache cache = new SnapshotCache(id -> snapshot(loads.incrementAndGet()),
                java.time.Duration.ofSeconds(60), now::get);

        assertThat(cache.get(1).version()).isEqualTo(1);
        now.addAndGet(java.time.Duration.ofSeconds(59).toNanos());
        assertThat(cache.get(1).version()).as("still fresh").isEqualTo(1);
        now.addAndGet(java.time.Duration.ofSeconds(1).toNanos());
        assertThat(cache.get(1).version()).as("expired, reloaded").isEqualTo(2);
        assertThat(cache.get(1).version()).isEqualTo(2);
    }

    @Test
    void zeroMaxAgeNeverExpires() {
        AtomicInteger loads = new AtomicInteger();
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        SnapshotCache cache = new SnapshotCache(id -> snapshot(loads.incrementAndGet()), java.time.Duration.ZERO, now::get);
        cache.get(1);
        now.addAndGet(java.time.Duration.ofDays(365).toNanos());
        assertThat(cache.get(1).version()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
