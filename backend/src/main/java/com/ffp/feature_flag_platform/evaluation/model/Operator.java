package com.ffp.feature_flag_platform.evaluation.model;

/**
 * Operators supported by targeting clauses. A clause matches when the context attribute
 * satisfies the operator for <em>any</em> of the clause values.
 */
public enum Operator {
    IN,
    STARTS_WITH,
    ENDS_WITH,
    CONTAINS,
    MATCHES,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    SEMVER_EQUAL,
    SEMVER_LESS_THAN,
    SEMVER_GREATER_THAN,
    BEFORE,
    AFTER,
    /** Values are segment keys; the clause attribute is ignored. */
    SEGMENT_MATCH;

    public boolean isNumeric() {
        return this == LESS_THAN || this == LESS_THAN_OR_EQUAL
                || this == GREATER_THAN || this == GREATER_THAN_OR_EQUAL;
    }

    public boolean isSemver() {
        return this == SEMVER_EQUAL || this == SEMVER_LESS_THAN || this == SEMVER_GREATER_THAN;
    }

    public boolean isDate() {
        return this == BEFORE || this == AFTER;
    }
}
