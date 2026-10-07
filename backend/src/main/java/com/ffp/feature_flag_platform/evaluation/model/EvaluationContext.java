package com.ffp.feature_flag_platform.evaluation.model;

import java.util.Map;
import java.util.Objects;

/**
 * The subject a flag is evaluated for (usually a user). {@code key} must be stable and unique;
 * it drives individual targeting and percentage bucketing.
 */
public record EvaluationContext(String key, Map<String, Object> attributes) {

    public static final String KEY_ATTRIBUTE = "key";

    public EvaluationContext {
        Objects.requireNonNull(key, "context key is required");
        attributes = attributes == null ? Map.of() : Map.copyOf(withoutNulls(attributes));
    }

    public static EvaluationContext of(String key) {
        return new EvaluationContext(key, Map.of());
    }

    /** Resolves an attribute; {@code "key"} always resolves to the context key. */
    public Object attribute(String name) {
        if (KEY_ATTRIBUTE.equals(name)) {
            return key;
        }
        return attributes.get(name);
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> attributes) {
        // Map.copyOf rejects null values (JSON nulls); treat them as absent attributes.
        var copy = new java.util.HashMap<String, Object>(attributes.size());
        attributes.forEach((k, v) -> {
            if (k != null && v != null) {
                copy.put(k, v);
            }
        });
        return copy;
    }
}
