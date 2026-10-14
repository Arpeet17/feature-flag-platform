package com.ffp.feature_flag_platform.rollout;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ffp.feature_flag_platform.common.Keys;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.Analysis;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class RolloutDtos {

    private RolloutDtos() {
    }

    /**
     * @param steps              treatment percentages, strictly increasing, ending at 100 (e.g. 1, 5, 25, 50, 100)
     * @param guardrailEventKey  event whose rate must not get significantly worse for treatment (e.g. "error");
     *                           without one the rollout advances on time (and traffic) alone
     */
    public record CreateRolloutRequest(@NotNull @PositiveOrZero Integer treatmentVariation,
                                       @NotNull @PositiveOrZero Integer controlVariation,
                                       @NotEmpty @Size(max = 20) List<@NotNull @Min(1) @Max(100) Integer> steps,
                                       @NotNull @Min(1) @Max(7 * 24 * 3600) Integer stepIntervalSeconds,
                                       @PositiveOrZero Integer minUsersPerStep,
                                       @Pattern(regexp = Keys.PATTERN, message = Keys.MESSAGE) String guardrailEventKey) {
    }

    public record HistoryEntry(Instant at, int step, int percentage, RolloutHistory.Action action, String detail) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RolloutResponse(Long id, String flagKey, String environmentKey, String environmentName,
                                  int treatmentVariation, String treatmentLabel, int controlVariation, String controlLabel,
                                  List<Integer> steps, int currentStep, int currentPercentage, Rollout.Status status,
                                  int stepIntervalSeconds, int minUsersPerStep, String guardrailEventKey,
                                  Instant stepStartedAt, Instant nextCheckAt, String lastDecision,
                                  String createdBy, Instant createdAt, List<HistoryEntry> history, Analysis guardrail,
                                  GuardrailTest guardrailTest) {
    }

    public enum GuardrailVerdict { INSUFFICIENT_DATA, NO_EVIDENCE, WORSE, BETTER }

    /**
     * The test that decides rollbacks: valid under continuous monitoring (see SequentialTest).
     *
     * @param alpha              significance level for this step (the rollout's α split across its guarded steps)
     * @param pValue             always-valid p-value; the rollout rolls back when it drops below alpha and treatment is worse
     * @param logLikelihoodRatio evidence that the rates differ (ln Λ)
     */
    public record GuardrailTest(String method, double alpha, double pValue, double logLikelihoodRatio,
                                GuardrailVerdict verdict) {
    }
}
