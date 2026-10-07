package com.ffp.feature_flag_platform.environment;

import com.ffp.feature_flag_platform.common.Keys;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class EnvironmentDtos {

    public static final String COLOR_PATTERN = "^#[0-9a-fA-F]{6}$";

    private EnvironmentDtos() {
    }

    public record CreateEnvironmentRequest(@NotBlank @Pattern(regexp = Keys.PATTERN, message = Keys.MESSAGE) String key,
                                           @NotBlank @Size(max = 100) String name,
                                           @Pattern(regexp = COLOR_PATTERN) String color) {
    }

    public record UpdateEnvironmentRequest(@NotBlank @Size(max = 100) String name,
                                           @Pattern(regexp = COLOR_PATTERN) String color,
                                           Integer sortOrder) {
    }

    public record EnvironmentResponse(Long id, String key, String name, String color, int sortOrder,
                                      String sdkKeyHint, Instant createdAt) {
        public static EnvironmentResponse from(Environment e) {
            return new EnvironmentResponse(e.getId(), e.getKey(), e.getName(), e.getColor(), e.getSortOrder(),
                    e.getSdkKeyHint(), e.getCreatedAt());
        }
    }

    /** Returned only on creation and rotation: the one time the raw SDK key is visible. */
    public record EnvironmentWithSdkKey(EnvironmentResponse environment, String sdkKey) {
    }
}
