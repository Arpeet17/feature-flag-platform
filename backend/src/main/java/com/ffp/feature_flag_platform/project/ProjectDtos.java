package com.ffp.feature_flag_platform.project;

import com.ffp.feature_flag_platform.common.Keys;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.EnvironmentResponse;
import com.ffp.feature_flag_platform.environment.EnvironmentDtos.EnvironmentWithSdkKey;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record CreateProjectRequest(@NotBlank @Pattern(regexp = Keys.PATTERN, message = Keys.MESSAGE) String key,
                                       @NotBlank @Size(max = 100) String name,
                                       @Size(max = 1000) String description) {
    }

    public record UpdateProjectRequest(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description) {
    }

    public record ProjectResponse(Long id, String key, String name, String description, long flagCount,
                                  List<EnvironmentResponse> environments, Instant createdAt, Instant updatedAt) {
    }

    /** Includes the raw SDK keys of the default environments; they are not retrievable later. */
    public record ProjectCreatedResponse(ProjectResponse project, List<EnvironmentWithSdkKey> environments) {
    }
}
