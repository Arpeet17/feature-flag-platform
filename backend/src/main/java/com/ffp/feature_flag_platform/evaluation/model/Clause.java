package com.ffp.feature_flag_platform.evaluation.model;

import java.util.List;

/**
 * A single condition, e.g. {@code country IN [US, CA]}. When {@code negate} is set the
 * result is inverted, which gives NOT_IN, NOT_CONTAINS, ... without extra operators.
 */
public record Clause(String attribute, Operator operator, List<String> values, boolean negate) {

    public Clause {
        values = values == null ? List.of() : List.copyOf(values);
    }

    public static Clause of(String attribute, Operator operator, String... values) {
        return new Clause(attribute, operator, List.of(values), false);
    }
}
