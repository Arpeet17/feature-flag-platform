package com.ffp.feature_flag_platform.evaluation.model;

import java.util.List;
import java.util.Set;

/**
 * A reusable audience. Explicit exclusions win over inclusions, which win over rules.
 */
public record SegmentDefinition(String key, Set<String> included, Set<String> excluded, List<SegmentRule> rules) {

    public SegmentDefinition {
        included = included == null ? Set.of() : Set.copyOf(included);
        excluded = excluded == null ? Set.of() : Set.copyOf(excluded);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** A segment rule: all clauses must match. Segment rules cannot reference other segments. */
    public record SegmentRule(List<Clause> clauses) {
        public SegmentRule {
            clauses = clauses == null ? List.of() : List.copyOf(clauses);
        }
    }
}
