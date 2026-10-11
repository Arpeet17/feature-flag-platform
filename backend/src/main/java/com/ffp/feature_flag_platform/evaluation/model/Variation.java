package com.ffp.feature_flag_platform.evaluation.model;

import tools.jackson.databind.JsonNode;

/**
 * One possible value a flag can serve. Variations are referenced by their index in the
 * flag's variation list, which keeps targeting rules independent of the actual values.
 */
public record Variation(JsonNode value, String name, String description) {
}
