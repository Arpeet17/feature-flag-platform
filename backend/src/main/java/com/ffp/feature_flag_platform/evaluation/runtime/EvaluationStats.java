package com.ffp.feature_flag_platform.evaluation.runtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight in-process evaluation counters per environment/flag/variation, powering the
 * "is this flag still used?" insight in the dashboard. {@link LongAdder} keeps contention
 * negligible on the hot path. Counts are per instance and reset on restart; a production
 * deployment would ship these to a time-series store (they are also exported via Micrometer).
 */
@Component
public class EvaluationStats {

    private final Map<Long, Map<String, FlagCounters>> counters = new ConcurrentHashMap<>();

    public void record(long environmentId, String flagKey, Integer variation) {
        counters.computeIfAbsent(environmentId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(flagKey, k -> new FlagCounters())
                .record(variation);
    }

    public FlagUsage usage(long environmentId, String flagKey) {
        FlagCounters c = counters.getOrDefault(environmentId, Map.of()).get(flagKey);
        return c == null ? FlagUsage.EMPTY : c.snapshot();
    }

    private static final class FlagCounters {
        private final Map<Integer, LongAdder> byVariation = new ConcurrentHashMap<>();
        private final AtomicLong lastEvaluated = new AtomicLong();

        void record(Integer variation) {
            byVariation.computeIfAbsent(variation == null ? -1 : variation, v -> new LongAdder()).increment();
            lastEvaluated.set(System.currentTimeMillis());
        }

        FlagUsage snapshot() {
            Map<Integer, Long> counts = new TreeMap<>();
            byVariation.forEach((k, v) -> counts.put(k, v.sum()));
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            long last = lastEvaluated.get();
            return new FlagUsage(total, counts, last == 0 ? null : Instant.ofEpochMilli(last));
        }
    }

    /** @param byVariation evaluation counts keyed by variation index; -1 counts errors */
    public record FlagUsage(long total, Map<Integer, Long> byVariation, Instant lastEvaluatedAt) {
        static final FlagUsage EMPTY = new FlagUsage(0, Map.of(), null);
    }
}
