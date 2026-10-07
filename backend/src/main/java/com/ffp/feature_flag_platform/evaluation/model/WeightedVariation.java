package com.ffp.feature_flag_platform.evaluation.model;

/** A variation and its share of a rollout, in units of {@link Rollout#TOTAL_WEIGHT}. */
public record WeightedVariation(int variation, int weight) {
}
