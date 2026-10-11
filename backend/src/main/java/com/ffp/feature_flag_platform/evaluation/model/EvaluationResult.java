package com.ffp.feature_flag_platform.evaluation.model;

import tools.jackson.databind.JsonNode;

/**
 * Outcome of evaluating one flag for one context. {@code variationIndex} is null when the
 * evaluation failed and the caller-supplied default should be used.
 */
public record EvaluationResult(String flagKey, JsonNode value, Integer variationIndex, EvaluationReason reason, long flagVersion) {

    public static EvaluationResult error(String flagKey, EvaluationReason.ErrorKind kind, JsonNode defaultValue, long version) {
        return new EvaluationResult(flagKey, defaultValue, null, EvaluationReason.error(kind), version);
    }

    public boolean isError() {
        return reason.kind() == EvaluationReason.Kind.ERROR;
    }
}
