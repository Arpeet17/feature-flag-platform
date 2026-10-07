package com.ffp.feature_flag_platform.evaluation.model;

import java.util.List;

/** A targeting rule. All clauses must match (logical AND); rules are evaluated in order. */
public record Rule(String id, String description, List<Clause> clauses, VariationOrRollout serve) {

    public Rule {
        clauses = clauses == null ? List.of() : List.copyOf(clauses);
    }
}
