package com.ffp.feature_flag_platform.evaluation.model;

import java.util.List;

/**
 * Immutable, environment-specific view of a flag: everything the evaluator needs and nothing
 * more. Built from the persistence model and cached in an environment snapshot.
 *
 * @param salt random per-flag value mixed into the bucketing hash so that the same user
 *             lands in uncorrelated buckets for different flags.
 */
public record FlagDefinition(
        String key,
        FlagType type,
        List<Variation> variations,
        boolean enabled,
        Targeting targeting,
        String salt,
        long version) {

    public FlagDefinition {
        variations = List.copyOf(variations);
    }

    public Variation variation(int index) {
        return index >= 0 && index < variations.size() ? variations.get(index) : null;
    }
}
