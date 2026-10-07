package com.ffp.feature_flag_platform.evaluation.model;

import java.util.List;

/**
 * Percentage rollout across variations. Weights are expressed in thousandths of a percent
 * so that {@value #TOTAL_WEIGHT} equals 100%, allowing rollouts as fine as 0.001%.
 *
 * @param bucketBy context attribute used for bucketing; defaults to the context key so a
 *                 user always lands in the same bucket. Use e.g. {@code companyId} to roll
 *                 out per organisation instead of per user.
 */
public record Rollout(List<WeightedVariation> variations, String bucketBy) {

    public static final int TOTAL_WEIGHT = 100_000;
    public static final String DEFAULT_BUCKET_BY = "key";

    public Rollout {
        variations = variations == null ? List.of() : List.copyOf(variations);
        bucketBy = bucketBy == null || bucketBy.isBlank() ? DEFAULT_BUCKET_BY : bucketBy;
    }
}
