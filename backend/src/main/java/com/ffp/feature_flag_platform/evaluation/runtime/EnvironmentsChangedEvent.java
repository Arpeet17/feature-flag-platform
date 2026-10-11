package com.ffp.feature_flag_platform.evaluation.runtime;

import java.util.Set;

/**
 * Published inside a write transaction whenever data affecting evaluation changes. Handled
 * after commit so caches are never refreshed with uncommitted (possibly rolled back) state.
 */
public record EnvironmentsChangedEvent(Set<Long> environmentIds) {

    public EnvironmentsChangedEvent {
        environmentIds = Set.copyOf(environmentIds);
    }

    public static EnvironmentsChangedEvent of(Long environmentId) {
        return new EnvironmentsChangedEvent(Set.of(environmentId));
    }
}
