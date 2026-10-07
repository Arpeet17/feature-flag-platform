package com.ffp.feature_flag_platform.evaluation.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Explains why a particular variation was served. Exposed to SDKs and the debugger UI;
 * invaluable when someone asks "why am I seeing the new checkout?".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvaluationReason(
        Kind kind,
        Integer ruleIndex,
        String ruleId,
        String prerequisiteKey,
        Boolean inRollout,
        ErrorKind errorKind) {

    public enum Kind { OFF, TARGET_MATCH, RULE_MATCH, FALLTHROUGH, PREREQUISITE_FAILED, ERROR }

    public enum ErrorKind { FLAG_NOT_FOUND, MALFORMED_FLAG, WRONG_TYPE }

    public static EvaluationReason off() {
        return new EvaluationReason(Kind.OFF, null, null, null, null, null);
    }

    public static EvaluationReason targetMatch() {
        return new EvaluationReason(Kind.TARGET_MATCH, null, null, null, null, null);
    }

    public static EvaluationReason ruleMatch(int index, String id, boolean inRollout) {
        return new EvaluationReason(Kind.RULE_MATCH, index, id, null, inRollout ? true : null, null);
    }

    public static EvaluationReason fallthrough(boolean inRollout) {
        return new EvaluationReason(Kind.FALLTHROUGH, null, null, null, inRollout ? true : null, null);
    }

    public static EvaluationReason prerequisiteFailed(String key) {
        return new EvaluationReason(Kind.PREREQUISITE_FAILED, null, null, key, null, null);
    }

    public static EvaluationReason error(ErrorKind errorKind) {
        return new EvaluationReason(Kind.ERROR, null, null, null, null, errorKind);
    }
}
