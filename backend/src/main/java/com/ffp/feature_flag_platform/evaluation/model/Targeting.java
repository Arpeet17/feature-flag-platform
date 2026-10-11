package com.ffp.feature_flag_platform.evaluation.model;

import java.util.List;

/**
 * Per-environment targeting configuration of a flag. Persisted as a single JSON document
 * because it is always read and written as an aggregate.
 */
public record Targeting(
        int offVariation,
        List<Prerequisite> prerequisites,
        List<Target> targets,
        List<Rule> rules,
        VariationOrRollout fallthrough) {

    public Targeting {
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        targets = targets == null ? List.of() : List.copyOf(targets);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** Default targeting: serve {@code onVariation} to everyone when on. */
    public static Targeting simple(int onVariation, int offVariation) {
        return new Targeting(offVariation, List.of(), List.of(), List.of(), VariationOrRollout.fixed(onVariation));
    }
}
