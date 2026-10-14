package com.ffp.feature_flag_platform.evaluation.runtime;

import com.ffp.feature_flag_platform.evaluation.engine.FlagEvaluator;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationContext;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationResult;
import com.ffp.feature_flag_platform.evaluation.model.FlagDefinition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Entry point for evaluation: snapshot lookup + evaluation + metrics. No database access on the hot path. */
@Service
public class EvaluationService {

    private final SnapshotCache cache;
    private final EvaluationStats stats;
    private final MeterRegistry meters;
    private final Timer batchTimer;

    public EvaluationService(SnapshotCache cache, EvaluationStats stats, MeterRegistry meters) {
        this.cache = cache;
        this.stats = stats;
        this.meters = meters;
        this.batchTimer = Timer.builder("ffp.evaluation.batch")
                .description("Time to evaluate all flags for one context")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
    }

    /** Evaluates every active flag in the environment (what client SDKs fetch on init). */
    public BatchResult evaluateAll(long environmentId, EvaluationContext context) {
        return evaluate(environmentId, context, null);
    }

    /**
     * @param flagKeys restrict to these flags; null means all flags. Unknown keys produce
     *                 FLAG_NOT_FOUND results rather than being silently dropped.
     */
    public BatchResult evaluate(long environmentId, EvaluationContext context, Collection<String> flagKeys) {
        return batchTimer.record(() -> {
            EnvironmentSnapshot snapshot = cache.get(environmentId);
            FlagEvaluator evaluator = snapshot.evaluator();
            Collection<String> keys = flagKeys == null ? new TreeSet<>(snapshot.flags().keySet()) : flagKeys;
            Map<String, EvaluationResult> results = new LinkedHashMap<>();
            for (String key : keys) {
                EvaluationResult result = evaluator.evaluate(key, context);
                results.put(key, result);
                record(snapshot, result);
            }
            return new BatchResult(snapshot.version(), results);
        });
    }

    public EvaluationResult evaluateOne(long environmentId, String flagKey, EvaluationContext context) {
        EnvironmentSnapshot snapshot = cache.get(environmentId);
        EvaluationResult result = snapshot.evaluator().evaluate(flagKey, context);
        record(snapshot, result);
        return result;
    }

    public FlagDefinition definition(long environmentId, String flagKey) {
        return cache.get(environmentId).flags().get(flagKey);
    }

    /** The full compiled ruleset, for SDKs that evaluate locally. */
    public EnvironmentSnapshot snapshot(long environmentId) {
        return cache.get(environmentId);
    }

    public long currentVersion(long environmentId) {
        return cache.get(environmentId).version();
    }

    private void record(EnvironmentSnapshot snapshot, EvaluationResult result) {
        stats.record(snapshot.environmentId(), result.flagKey(), result.variationIndex());
        // Flag keys are bounded per project, so tagging by flag keeps cardinality under control.
        Counter.builder("ffp.evaluations")
                .tag("environment", snapshot.environmentKey())
                .tag("flag", result.isError() ? "_unknown" : result.flagKey())
                .tag("reason", result.reason().kind().name())
                .register(meters)
                .increment();
    }

    public record BatchResult(long version, Map<String, EvaluationResult> results) {
    }
}
