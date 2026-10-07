package com.ffp.feature_flag_platform.evaluation.model;

import java.util.Set;

/** Individual targeting: the given context keys always receive {@code variation}. */
public record Target(int variation, Set<String> values) {

    public Target {
        values = values == null ? Set.of() : Set.copyOf(values);
    }
}
