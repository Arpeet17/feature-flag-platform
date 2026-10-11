package com.ffp.feature_flag_platform.segment;

import com.ffp.feature_flag_platform.common.Keys;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition.SegmentRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class SegmentDtos {

    private SegmentDtos() {
    }

    public record CreateSegmentRequest(@NotBlank @Pattern(regexp = Keys.PATTERN, message = Keys.MESSAGE) String key,
                                       @NotBlank @Size(max = 100) String name,
                                       @Size(max = 1000) String description,
                                       @Size(max = 10_000) Set<String> included,
                                       @Size(max = 10_000) Set<String> excluded,
                                       List<SegmentRule> rules) {
    }

    public record UpdateSegmentRequest(@NotBlank @Size(max = 100) String name,
                                       @Size(max = 1000) String description,
                                       @Size(max = 10_000) Set<String> included,
                                       @Size(max = 10_000) Set<String> excluded,
                                       List<SegmentRule> rules,
                                       Long version) {
    }

    public record SegmentResponse(String key, String name, String description, Set<String> included, Set<String> excluded,
                                  List<SegmentRule> rules, long version, List<String> usedByFlags,
                                  Instant createdAt, Instant updatedAt) {
    }
}
