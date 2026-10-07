package com.ffp.feature_flag_platform.evaluation.engine;

import com.ffp.feature_flag_platform.evaluation.model.EvaluationContext;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationReason;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationReason.ErrorKind;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationResult;
import com.ffp.feature_flag_platform.evaluation.model.FlagDefinition;
import com.ffp.feature_flag_platform.evaluation.model.Prerequisite;
import com.ffp.feature_flag_platform.evaluation.model.Rollout;
import com.ffp.feature_flag_platform.evaluation.model.Rule;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition;
import com.ffp.feature_flag_platform.evaluation.model.Target;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.model.Variation;
import com.ffp.feature_flag_platform.evaluation.model.VariationOrRollout;
import com.ffp.feature_flag_platform.evaluation.model.WeightedVariation;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure, side-effect free flag evaluation. Evaluation order:
 * <ol>
 *   <li>Flag off → off variation</li>
 *   <li>Any prerequisite not satisfied → off variation</li>
 *   <li>Individual targets</li>
 *   <li>Rules, first match wins</li>
 *   <li>Fallthrough (default rule)</li>
 * </ol>
 * Thread-safe: holds only immutable lookups, so one instance serves all requests for an
 * environment snapshot.
 */
public final class FlagEvaluator {

    private final Function<String, FlagDefinition> flags;
    private final Function<String, SegmentDefinition> segments;

    public FlagEvaluator(Function<String, FlagDefinition> flags, Function<String, SegmentDefinition> segments) {
        this.flags = flags;
        this.segments = segments;
    }

    public EvaluationResult evaluate(String flagKey, EvaluationContext context) {
        FlagDefinition flag = flags.apply(flagKey);
        if (flag == null) {
            return EvaluationResult.error(flagKey, ErrorKind.FLAG_NOT_FOUND, null, 0);
        }
        return evaluate(flag, context, new HashSet<>());
    }

    public EvaluationResult evaluate(FlagDefinition flag, EvaluationContext context) {
        return evaluate(flag, context, new HashSet<>());
    }

    private EvaluationResult evaluate(FlagDefinition flag, EvaluationContext context, Set<String> visiting) {
        if (!visiting.add(flag.key())) {
            // Prerequisite cycle. Validation prevents this on write; be defensive on read.
            return EvaluationResult.error(flag.key(), ErrorKind.MALFORMED_FLAG, null, flag.version());
        }
        try {
            Targeting targeting = flag.targeting();
            if (!flag.enabled()) {
                return serve(flag, targeting.offVariation(), EvaluationReason.off());
            }

            for (Prerequisite prerequisite : targeting.prerequisites()) {
                if (!prerequisiteSatisfied(prerequisite, context, visiting)) {
                    return serve(flag, targeting.offVariation(), EvaluationReason.prerequisiteFailed(prerequisite.flagKey()));
                }
            }

            for (Target target : targeting.targets()) {
                if (target.values().contains(context.key())) {
                    return serve(flag, target.variation(), EvaluationReason.targetMatch());
                }
            }

            for (int i = 0; i < targeting.rules().size(); i++) {
                Rule rule = targeting.rules().get(i);
                if (ruleMatches(rule, context)) {
                    int ruleIndex = i;
                    return serveVariationOrRollout(flag, rule.serve(), context,
                            inRollout -> EvaluationReason.ruleMatch(ruleIndex, rule.id(), inRollout));
                }
            }

            return serveVariationOrRollout(flag, targeting.fallthrough(), context, EvaluationReason::fallthrough);
        } finally {
            visiting.remove(flag.key());
        }
    }

    private boolean prerequisiteSatisfied(Prerequisite prerequisite, EvaluationContext context, Set<String> visiting) {
        FlagDefinition prereqFlag = flags.apply(prerequisite.flagKey());
        if (prereqFlag == null || !prereqFlag.enabled()) {
            return false;
        }
        EvaluationResult result = evaluate(prereqFlag, context, visiting);
        return !result.isError()
                && result.variationIndex() != null
                && result.variationIndex() == prerequisite.variation();
    }

    private boolean ruleMatches(Rule rule, EvaluationContext context) {
        if (rule.clauses().isEmpty()) {
            return false;
        }
        return rule.clauses().stream()
                .allMatch(c -> ClauseMatcher.matches(c, context, segmentKey -> segmentMatches(segmentKey, context)));
    }

    private boolean segmentMatches(String segmentKey, EvaluationContext context) {
        SegmentDefinition segment = segments.apply(segmentKey);
        if (segment == null) {
            return false;
        }
        if (segment.excluded().contains(context.key())) {
            return false;
        }
        if (segment.included().contains(context.key())) {
            return true;
        }
        return segment.rules().stream().anyMatch(r -> !r.clauses().isEmpty()
                && r.clauses().stream().allMatch(c -> ClauseMatcher.matches(c, context, null)));
    }

    private EvaluationResult serveVariationOrRollout(FlagDefinition flag, VariationOrRollout serve, EvaluationContext context,
                                                     Function<Boolean, EvaluationReason> reason) {
        if (serve == null) {
            return EvaluationResult.error(flag.key(), ErrorKind.MALFORMED_FLAG, null, flag.version());
        }
        if (serve.variation() != null) {
            return serve(flag, serve.variation(), reason.apply(false));
        }
        Rollout rollout = serve.rollout();
        if (rollout == null || rollout.variations().isEmpty()) {
            return EvaluationResult.error(flag.key(), ErrorKind.MALFORMED_FLAG, null, flag.version());
        }
        return serve(flag, pickVariation(flag, rollout, context), reason.apply(true));
    }

    static int pickVariation(FlagDefinition flag, Rollout rollout, EvaluationContext context) {
        Object bucketValue = context.attribute(rollout.bucketBy());
        // Contexts without the bucketing attribute all share bucket 0 (deterministic, documented).
        int bucket = bucketValue == null ? 0 : Bucketer.bucket(flag.key(), flag.salt(), String.valueOf(bucketValue));
        int cumulative = 0;
        for (WeightedVariation wv : rollout.variations()) {
            cumulative += wv.weight();
            if (bucket < cumulative) {
                return wv.variation();
            }
        }
        // Weights summing to < 100% (rejected by validation) land in the last variation.
        return rollout.variations().getLast().variation();
    }

    private static EvaluationResult serve(FlagDefinition flag, int index, EvaluationReason reason) {
        Variation variation = flag.variation(index);
        if (variation == null) {
            return EvaluationResult.error(flag.key(), ErrorKind.MALFORMED_FLAG, null, flag.version());
        }
        return new EvaluationResult(flag.key(), variation.value(), index, reason, flag.version());
    }
}
