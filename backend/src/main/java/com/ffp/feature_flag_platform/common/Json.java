package com.ffp.feature_flag_platform.common;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared mapper for persistence-level JSON (JPA converters, audit snapshots). Kept separate
 * from the HTTP mapper so API serialization tweaks can never change what is stored.
 */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Omitted booleans/ints (e.g. Clause.negate) default to false/0, as in Jackson 2.
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private Json() {
    }

    public static String write(Object value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return json == null ? null : MAPPER.readValue(json, type);
    }

    public static <T> T read(String json, TypeReference<T> type) {
        return json == null ? null : MAPPER.readValue(json, type);
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }
}
