package com.ffp.feature_flag_platform.flag;

import com.ffp.feature_flag_platform.common.Keys;
import com.ffp.feature_flag_platform.evaluation.model.FlagType;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.model.Variation;
import com.ffp.feature_flag_platform.evaluation.runtime.EvaluationStats.FlagUsage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FlagDtos {

    private FlagDtos() {
    }

    /**
     * @param variations optional for BOOLEAN flags (defaults to true/false)
     */
    public record CreateFlagRequest(@NotBlank @Pattern(regexp = Keys.PATTERN, message = Keys.MESSAGE) String key,
                                    @NotBlank @Size(max = 100) String name,
                                    @Size(max = 1000) String description,
                                    @NotNull FlagType type,
                                    List<Variation> variations,
                                    Integer defaultOnVariation,
                                    Integer defaultOffVariation,
                                    Set<@Pattern(regexp = Keys.PATTERN, message = Keys.MESSAGE) String> tags,
                                    Boolean temporary) {
    }

    /** Partial update: null fields are left unchanged. */
    public record UpdateFlagRequest(@Size(min = 1, max = 100) String name,
                                    @Size(max = 1000) String description,
                                    Set<@Pattern(regexp = Keys.PATTERN, message = Keys.MESSAGE) String> tags,
                                    Boolean temporary,
                                    List<Variation> variations,
                                    Integer defaultOnVariation,
                                    Integer defaultOffVariation) {
    }

    /**
     * @param version the config version the client last read; a mismatch yields 409 instead of
     *                silently overwriting someone else's change.
     */
    public record UpdateTargetingRequest(@NotNull Targeting targeting, Boolean enabled, @NotNull Long version,
                                         @Size(max = 300) String comment) {
    }

    public record ToggleRequest(@NotNull Boolean enabled, @Size(max = 300) String comment) {
    }

    /** Promote targeting from one environment to another (e.g. staging → production). */
    public record CopyTargetingRequest(@NotBlank String sourceEnvironment, boolean includeEnabledState,
                                       @NotNull Long targetVersion, @Size(max = 300) String comment) {
    }

    public record EnvironmentState(boolean enabled, long version, String summary, Instant updatedAt) {
    }

    public record FlagSummaryResponse(String key, String name, String description, FlagType type, List<Variation> variations,
                                      Set<String> tags, boolean temporary, boolean archived, FlagLifecycle lifecycle,
                                      Map<String, EnvironmentState> environments, Instant createdAt, Instant updatedAt) {
    }

    public record FlagConfigResponse(String environmentKey, String environmentName, boolean enabled, Targeting targeting,
                                     long version, String updatedBy, Instant updatedAt, FlagUsage usage) {
    }

    public record FlagResponse(String key, String name, String description, FlagType type, List<Variation> variations,
                               int defaultOnVariation, int defaultOffVariation, Set<String> tags, boolean temporary,
                               boolean archived, FlagLifecycle lifecycle, long version, String createdBy,
                               Instant createdAt, Instant updatedAt, List<FlagConfigResponse> environments,
                               List<String> dependentFlags) {
    }
}
