package com.ffp.feature_flag_platform.evaluation.model;

/** What to serve when a rule (or the fallthrough) applies: a fixed variation or a rollout. */
public record VariationOrRollout(Integer variation, Rollout rollout) {

    public static VariationOrRollout fixed(int variation) {
        return new VariationOrRollout(variation, null);
    }

    public static VariationOrRollout rollout(Rollout rollout) {
        return new VariationOrRollout(null, rollout);
    }
}
