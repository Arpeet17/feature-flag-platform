package com.ffp.feature_flag_platform.rollout;

import com.ffp.feature_flag_platform.auth.SystemActor;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.model.VariationOrRollout;
import com.ffp.feature_flag_platform.evaluation.model.WeightedVariation;
import com.ffp.feature_flag_platform.experiment.EventStore;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.Analysis;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.ArmData;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.Comparison;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.MetricType;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.Verdict;
import com.ffp.feature_flag_platform.flag.FlagDtos.FlagConfigResponse;
import com.ffp.feature_flag_platform.flag.FlagDtos.UpdateTargetingRequest;
import com.ffp.feature_flag_platform.flag.FlagService;
import com.ffp.feature_flag_platform.rollout.RolloutHistory.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The decision loop for guarded rollouts. Each check, for a RUNNING rollout:
 * <ol>
 *   <li>Pause if a human changed the flag (its targeting version moved) or turned it off.</li>
 *   <li>Analyse the guardrail metric for users first exposed during the current step: roll back
 *       if treatment is significantly worse, or if the sample ratio is off (assignment broken).</li>
 *   <li>Otherwise advance once the step's time has elapsed and enough treatment users were seen;
 *       reaching 100% completes the rollout.</li>
 * </ol>
 * The guardrail is checked several times per step, so a bad release is rolled back as soon as
 * the evidence is significant rather than at the end of the step.
 */
@Component
public class RolloutEngine {

    private static final Logger log = LoggerFactory.getLogger(RolloutEngine.class);
    static final double ALPHA = 0.05;

    public enum Outcome { SKIPPED, HELD, ADVANCED, COMPLETED, ROLLED_BACK, PAUSED }

    private final RolloutRepository rollouts;
    private final RolloutHistoryRepository history;
    private final RolloutLock lock;
    private final FlagService flags;
    private final EventStore events;
    private final TransactionTemplate tx;

    public RolloutEngine(RolloutRepository rollouts, RolloutHistoryRepository history, RolloutLock lock, FlagService flags,
                         EventStore events, TransactionTemplate tx) {
        this.rollouts = rollouts;
        this.history = history;
        this.lock = lock;
        this.flags = flags;
        this.events = events;
        this.tx = tx;
    }

    /** Processes one rollout in its own transaction. Safe to call concurrently from any instance. */
    public Outcome process(long rolloutId, Instant now) {
        Outcome outcome = tx.execute(status -> {
            if (!lock.tryClaim(rolloutId)) {
                return Outcome.SKIPPED; // another instance is processing it right now
            }
            Rollout r = rollouts.findWithEnvironment(rolloutId).orElse(null);
            if (r == null || r.getStatus() != Rollout.Status.RUNNING || now.isBefore(r.getNextCheckAt())) {
                return Outcome.SKIPPED;
            }
            Outcome result = decide(r, now);
            r.setNextCheckAt(now.plus(checkInterval(r)));
            return result;
        });
        if (outcome != Outcome.SKIPPED && outcome != Outcome.HELD) {
            log.info("Rollout {} → {}", rolloutId, outcome);
        }
        return outcome;
    }

    private Outcome decide(Rollout r, Instant now) {
        FlagConfigResponse config = config(r);
        if (config.version() != r.getExpectedConfigVersion()) {
            return pause(r, now, "The flag's targeting was changed outside the rollout (version %d → %d). Paused so the "
                    .formatted(r.getExpectedConfigVersion(), config.version())
                    + "rollout doesn't overwrite that change; resume to continue from the current step.");
        }
        if (!config.enabled()) {
            return pause(r, now, "The flag was turned off. Paused; resume after turning it back on.");
        }

        int percentage = r.currentPercentage();
        Analysis guardrail = guardrail(r, now);
        if (guardrail != null) {
            if (guardrail.sampleRatio().mismatch()) {
                return rollBack(r, now, "Sample ratio mismatch (p = %.2g): users aren't split %d/%d as configured, so the "
                        .formatted(guardrail.sampleRatio().pValue(), percentage, 100 - percentage)
                        + "guardrail can't be trusted. Rolled back to be safe.");
            }
            Comparison c = guardrail.comparisons().getFirst();
            if (c.verdict() == Verdict.WORSE) {
                return rollBack(r, now, "Guardrail '%s' significantly worse at %d%%: %s (p = %.2g). Rolled back."
                        .formatted(r.getGuardrailEventKey(), percentage, describe(guardrail), c.pValue()));
            }
        }

        Duration elapsed = Duration.between(r.getStepStartedAt(), now);
        Duration interval = Duration.ofSeconds(r.getStepIntervalSeconds());
        long treatmentUsers = events.countExposures(r.getEnvironment().getId(), r.getFlagKey(), r.getTreatmentVariation(),
                r.getStepStartedAt(), now);
        String health = guardrail == null ? "" : " Guardrail: " + describe(guardrail) + ".";
        if (elapsed.compareTo(interval) < 0) {
            r.setLastDecision("Holding at %d%%: %s left in this step.%s".formatted(percentage,
                    human(interval.minus(elapsed)), health));
            return Outcome.HELD;
        }
        if (treatmentUsers < r.getMinUsersPerStep()) {
            r.setLastDecision("Holding at %d%%: waiting for traffic (%d of %d treatment users this step).%s"
                    .formatted(percentage, treatmentUsers, r.getMinUsersPerStep(), health));
            return Outcome.HELD;
        }
        return advance(r, now, "Step %d at %d%% passed (%d treatment users).%s"
                .formatted(r.getCurrentStep() + 1, percentage, treatmentUsers, health));
    }

    /**
     * Guardrail analysis for users first exposed during the current step. Restricting to the
     * step keeps the comparison valid: within it, assignment follows one fixed split.
     */
    Analysis guardrail(Rollout r, Instant now) {
        if (r.getGuardrailEventKey() == null || r.currentPercentage() >= 100) {
            return null;
        }
        Map<Integer, ArmData> arms = new java.util.HashMap<>();
        for (ArmData a : events.aggregate(r.getEnvironment().getId(), r.getFlagKey(), r.getGuardrailEventKey(),
                r.getStepStartedAt(), now)) {
            arms.put(a.variation(), a);
        }
        int t = r.getTreatmentVariation();
        int c = r.getControlVariation();
        List<ArmData> pair = List.of(
                withLabel(arms.getOrDefault(c, new ArmData(c, null, 0, 0, 0, 0)), "control"),
                withLabel(arms.getOrDefault(t, new ArmData(t, null, 0, 0, 0, 0)), "treatment"));
        int pct = r.currentPercentage();
        return ExperimentAnalyzer.analyze(pair, c, MetricType.CONVERSION, false, ALPHA,
                Map.of(t, pct * 1_000, c, (100 - pct) * 1_000));
    }

    // ------------------------------------------------------------------ transitions

    Outcome advance(Rollout r, Instant now, String why) {
        int next = r.getCurrentStep() + 1;
        r.moveTo(next, now);
        boolean done = r.isLastStep();
        apply(r, done ? VariationOrRollout.fixed(r.getTreatmentVariation()) : split(r),
                done ? "Guarded rollout completed: 100% of traffic on the new variation."
                        : "Guarded rollout advanced to %d%%.".formatted(r.currentPercentage()));
        if (done) {
            r.setStatus(Rollout.Status.COMPLETED);
        }
        record(r, now, done ? Action.COMPLETED : Action.ADVANCED, why);
        r.setLastDecision(done ? "Completed: 100% on the new variation." : "Advanced to %d%%. %s".formatted(r.currentPercentage(), why));
        return done ? Outcome.COMPLETED : Outcome.ADVANCED;
    }

    Outcome rollBack(Rollout r, Instant now, String why) {
        apply(r, VariationOrRollout.fixed(r.getControlVariation()), "Guarded rollout rolled back: " + why);
        r.setStatus(Rollout.Status.ROLLED_BACK);
        record(r, now, Action.ROLLED_BACK, why);
        r.setLastDecision(why);
        return Outcome.ROLLED_BACK;
    }

    private Outcome pause(Rollout r, Instant now, String why) {
        r.setStatus(Rollout.Status.PAUSED);
        record(r, now, Action.PAUSED, why);
        r.setLastDecision(why);
        return Outcome.PAUSED;
    }

    /**
     * Rewrites only the default rule of the flag's targeting (rules and targets are untouched).
     * Treatment comes first in the rollout so it owns the lowest buckets: raising the
     * percentage only ever adds users to treatment, nobody flips back and forth.
     */
    void apply(Rollout r, VariationOrRollout fallthrough, String comment) {
        FlagConfigResponse config = config(r);
        Targeting t = config.targeting();
        Targeting next = new Targeting(t.offVariation(), t.prerequisites(), t.targets(), t.rules(), fallthrough);
        FlagConfigResponse updated = SystemActor.runAs("rollout-bot", () -> flags.updateTargeting(
                r.getProject().getKey(), r.getFlagKey(), r.getEnvironment().getKey(),
                new UpdateTargetingRequest(next, true, config.version(), comment)));
        r.setExpectedConfigVersion(updated.version());
    }

    static VariationOrRollout split(Rollout r) {
        int weight = r.currentPercentage() * 1_000;
        return VariationOrRollout.rollout(new com.ffp.feature_flag_platform.evaluation.model.Rollout(List.of(
                new WeightedVariation(r.getTreatmentVariation(), weight),
                new WeightedVariation(r.getControlVariation(), 100_000 - weight)), "key"));
    }

    void record(Rollout r, Instant now, Action action, String detail) {
        history.save(new RolloutHistory(r.getId(), now, r.getCurrentStep(), r.currentPercentage(), action, detail));
    }

    FlagConfigResponse config(Rollout r) {
        return flags.getConfig(r.getProject().getKey(), r.getFlagKey(), r.getEnvironment().getKey());
    }

    /** Check several times per step so regressions are caught early, but not more than every 2s or less than every minute. */
    static Duration checkInterval(Rollout r) {
        long seconds = Math.clamp(r.getStepIntervalSeconds() / 5, 2, 60);
        return Duration.ofSeconds(seconds);
    }

    static String describe(Analysis a) {
        var treatment = a.arms().stream().filter(x -> x.variation() != a.controlVariation()).findFirst().orElseThrow();
        var control = a.arms().stream().filter(x -> x.variation() == a.controlVariation()).findFirst().orElseThrow();
        return "treatment %.2f%% of %d users vs control %.2f%% of %d".formatted(
                treatment.estimate() * 100, treatment.users(), control.estimate() * 100, control.users());
    }

    private static ArmData withLabel(ArmData a, String label) {
        return new ArmData(a.variation(), label, a.users(), a.conversions(), a.sum(), a.sumSquares());
    }

    private static String human(Duration d) {
        long s = Math.max(0, d.toSeconds());
        return s >= 3600 ? "%dh %02dm".formatted(s / 3600, s % 3600 / 60) : s >= 60 ? "%dm %02ds".formatted(s / 60, s % 60) : s + "s";
    }
}
