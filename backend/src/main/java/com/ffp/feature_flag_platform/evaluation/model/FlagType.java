package com.ffp.feature_flag_platform.evaluation.model;

import tools.jackson.databind.JsonNode;

public enum FlagType {
    BOOLEAN,
    STRING,
    NUMBER,
    JSON;

    public boolean accepts(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        return switch (this) {
            case BOOLEAN -> value.isBoolean();
            case STRING -> value.isString();
            case NUMBER -> value.isNumber();
            case JSON -> value.isObject() || value.isArray();
        };
    }
}
