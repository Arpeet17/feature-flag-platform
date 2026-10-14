package com.ffp.feature_flag_platform.experiment;

import com.ffp.feature_flag_platform.common.Keys;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.Analysis;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.MetricType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ExperimentDtos {

    private ExperimentDtos() {
    }

    /**
     * One SDK event. {@code exposure}: the context was served {@code variation} of {@code flagKey}
     * ({@code inExperiment} = assigned by a percentage rollout). {@code custom}: the context did
     * {@code eventKey}, optionally with a numeric {@code value} (e.g. order total).
     */
    public record SdkEvent(@NotBlank @Pattern(regexp = "exposure|custom") String kind,
                           @NotBlank @Size(max = 256) String contextKey,
                           @Size(max = 64) String flagKey,
                           @PositiveOrZero Integer variation,
                           Boolean inExperiment,
                           @Pattern(regexp = Keys.PATTERN, message = Keys.MESSAGE) String eventKey,
                           Double value,
                           Long timestamp) {
    }

    public record EventBatch(@NotNull @Size(max = 1000) List<@Valid SdkEvent> events) {
    }

    public record IngestResult(int accepted, int rejected) {
    }

    public record CreateExperimentRequest(@NotBlank @Pattern(regexp = Keys.PATTERN, message = Keys.MESSAGE) String key,
                                          @NotBlank @Size(max = 100) String name,
                                          @Size(max = 1000) String hypothesis,
                                          @NotBlank String environment,
                                          @NotBlank String flagKey,
                                          @NotBlank @Pattern(regexp = Keys.PATTERN, message = Keys.MESSAGE) String metricEventKey,
                                          @NotNull MetricType metricType,
                                          Boolean higherIsBetter,
                                          @PositiveOrZero Integer controlVariation) {
    }

    public record ExperimentResponse(String key, String name, String hypothesis, String environmentKey, String environmentName,
                                     String flagKey, String metricEventKey, MetricType metricType, boolean higherIsBetter,
                                     int controlVariation, Experiment.Status status, Instant startedAt, Instant stoppedAt,
                                     String createdBy, @Valid Results results) {
    }

    /**
     * @param excludedNonRandomized exposures in the window served by targeting rules rather than
     *                              a rollout; excluded because they were not randomly assigned
     */
    public record Results(Instant from, Instant to, long excludedNonRandomized, Analysis analysis) {
    }
}
