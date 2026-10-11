package com.ffp.feature_flag_platform.evaluation.runtime;

import com.ffp.feature_flag_platform.evaluation.engine.FlagEvaluator;
import com.ffp.feature_flag_platform.evaluation.model.FlagDefinition;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition;

import java.util.Map;

/**
 * Immutable, fully materialised view of one environment. Evaluation works exclusively on
 * snapshots so the hot path never touches the database and readers never see a
 * half-applied change.
 *
 * @param version monotonically increasing per process; SDKs use it to detect changes.
 */
public record EnvironmentSnapshot(long environmentId, String environmentKey, long version,
                                  Map<String, FlagDefinition> flags, Map<String, SegmentDefinition> segments) {

    public EnvironmentSnapshot {
        flags = Map.copyOf(flags);
        segments = Map.copyOf(segments);
    }

    public FlagEvaluator evaluator() {
        return new FlagEvaluator(flags::get, segments::get);
    }
}
