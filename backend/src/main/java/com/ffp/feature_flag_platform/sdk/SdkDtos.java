package com.ffp.feature_flag_platform.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationContext;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationReason;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationResult;
import com.ffp.feature_flag_platform.evaluation.runtime.EvaluationService.BatchResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SdkDtos {

    private SdkDtos() {
    }

    public record ContextDto(@NotBlank @Size(max = 256) String key, @Size(max = 100) Map<String, Object> attributes) {
        public EvaluationContext toDomain() {
            return new EvaluationContext(key, attributes);
        }
    }

    /** @param flagKeys optional subset of flags to evaluate */
    public record EvaluateRequest(@NotNull @Valid ContextDto context, @Size(max = 500) List<String> flagKeys) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlagValue(JsonNode value, Integer variation, EvaluationReason reason, long version) {
        public static FlagValue from(EvaluationResult r) {
            return new FlagValue(r.value(), r.variationIndex(), r.reason(), r.flagVersion());
        }
    }

    /** @param version snapshot version; changes whenever any flag in the environment changes */
    public record EvaluationResponse(long version, Map<String, FlagValue> flags) {
        public static EvaluationResponse from(BatchResult batch) {
            Map<String, FlagValue> flags = new LinkedHashMap<>();
            batch.results().forEach((k, v) -> flags.put(k, FlagValue.from(v)));
            return new EvaluationResponse(batch.version(), flags);
        }
    }
}
