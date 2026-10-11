package com.ffp.feature_flag_platform.evaluation.engine;

import com.ffp.feature_flag_platform.evaluation.model.Clause;
import com.ffp.feature_flag_platform.evaluation.model.Operator;
import com.ffp.feature_flag_platform.evaluation.model.Prerequisite;
import com.ffp.feature_flag_platform.evaluation.model.Rollout;
import com.ffp.feature_flag_platform.evaluation.model.Rule;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition.SegmentRule;
import com.ffp.feature_flag_platform.evaluation.model.Target;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.model.VariationOrRollout;
import com.ffp.feature_flag_platform.evaluation.model.WeightedVariation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates targeting on write so evaluation never has to deal with malformed configuration.
 * Returns human-readable errors with JSON-path-like locations for the UI to display.
 * Also enforces size limits that bound worst-case evaluation cost.
 */
public final class TargetingValidator {

    public static final int MAX_RULES = 100;
    public static final int MAX_CLAUSES_PER_RULE = 20;
    public static final int MAX_CLAUSE_VALUES = 1_000;
    public static final int MAX_TARGET_VALUES = 10_000;

    private TargetingValidator() {
    }

    /**
     * @param prerequisiteVariationCount number of variations of a prerequisite flag, or null if it does not exist
     */
    public static List<String> validate(String flagKey, Targeting targeting, int variationCount, Set<String> segmentKeys,
                                        Function<String, Integer> prerequisiteVariationCount) {
        List<String> errors = new ArrayList<>();
        if (targeting == null) {
            return List.of("targeting is required");
        }
        checkVariation(targeting.offVariation(), variationCount, "offVariation", errors);

        Set<String> prereqKeys = new HashSet<>();
        for (int i = 0; i < targeting.prerequisites().size(); i++) {
            Prerequisite p = targeting.prerequisites().get(i);
            String path = "prerequisites[%d]".formatted(i);
            if (p.flagKey() == null || p.flagKey().isBlank()) {
                errors.add(path + ".flagKey is required");
                continue;
            }
            if (p.flagKey().equals(flagKey)) {
                errors.add(path + ": a flag cannot be its own prerequisite");
                continue;
            }
            if (!prereqKeys.add(p.flagKey())) {
                errors.add(path + ": duplicate prerequisite '%s'".formatted(p.flagKey()));
            }
            Integer count = prerequisiteVariationCount.apply(p.flagKey());
            if (count == null) {
                errors.add(path + ": flag '%s' does not exist or is archived".formatted(p.flagKey()));
            } else {
                checkVariation(p.variation(), count, path + ".variation", errors);
            }
        }

        Set<String> targetedKeys = new HashSet<>();
        int totalTargets = 0;
        for (int i = 0; i < targeting.targets().size(); i++) {
            Target t = targeting.targets().get(i);
            String path = "targets[%d]".formatted(i);
            checkVariation(t.variation(), variationCount, path + ".variation", errors);
            totalTargets += t.values().size();
            for (String key : t.values()) {
                if (!targetedKeys.add(key)) {
                    errors.add(path + ": context '%s' is targeted more than once".formatted(key));
                }
            }
        }
        if (totalTargets > MAX_TARGET_VALUES) {
            errors.add("targets: at most %d individually targeted contexts (use a segment instead)".formatted(MAX_TARGET_VALUES));
        }

        if (targeting.rules().size() > MAX_RULES) {
            errors.add("rules: at most %d rules allowed".formatted(MAX_RULES));
        }
        Set<String> ruleIds = new HashSet<>();
        for (int i = 0; i < targeting.rules().size(); i++) {
            Rule rule = targeting.rules().get(i);
            String path = "rules[%d]".formatted(i);
            if (rule.id() != null && !ruleIds.add(rule.id())) {
                errors.add(path + ": duplicate rule id '%s'".formatted(rule.id()));
            }
            if (rule.clauses().isEmpty()) {
                errors.add(path + ": a rule needs at least one clause");
            }
            if (rule.clauses().size() > MAX_CLAUSES_PER_RULE) {
                errors.add(path + ": at most %d clauses per rule".formatted(MAX_CLAUSES_PER_RULE));
            }
            for (int j = 0; j < rule.clauses().size(); j++) {
                validateClause(rule.clauses().get(j), path + ".clauses[%d]".formatted(j), segmentKeys, errors);
            }
            validateServe(rule.serve(), variationCount, path + ".serve", errors);
        }

        validateServe(targeting.fallthrough(), variationCount, "fallthrough", errors);
        return errors;
    }

    public static List<String> validateSegmentRules(List<SegmentRule> rules) {
        List<String> errors = new ArrayList<>();
        if (rules.size() > MAX_RULES) {
            errors.add("rules: at most %d rules allowed".formatted(MAX_RULES));
        }
        for (int i = 0; i < rules.size(); i++) {
            SegmentRule rule = rules.get(i);
            String path = "rules[%d]".formatted(i);
            if (rule.clauses().isEmpty()) {
                errors.add(path + ": a rule needs at least one clause");
            }
            for (int j = 0; j < rule.clauses().size(); j++) {
                Clause clause = rule.clauses().get(j);
                String clausePath = path + ".clauses[%d]".formatted(j);
                if (clause.operator() == Operator.SEGMENT_MATCH) {
                    errors.add(clausePath + ": segments cannot reference other segments");
                } else {
                    validateClause(clause, clausePath, Set.of(), errors);
                }
            }
        }
        return errors;
    }

    private static void validateClause(Clause clause, String path, Set<String> segmentKeys, List<String> errors) {
        if (clause.operator() == null) {
            errors.add(path + ".operator is required");
            return;
        }
        if (clause.values().isEmpty()) {
            errors.add(path + ".values must not be empty");
        }
        if (clause.values().size() > MAX_CLAUSE_VALUES) {
            errors.add(path + ".values: at most %d values (use a segment instead)".formatted(MAX_CLAUSE_VALUES));
        }
        Operator op = clause.operator();
        if (op == Operator.SEGMENT_MATCH) {
            for (String key : clause.values()) {
                if (!segmentKeys.contains(key)) {
                    errors.add(path + ": segment '%s' does not exist".formatted(key));
                }
            }
            return;
        }
        if (clause.attribute() == null || clause.attribute().isBlank()) {
            errors.add(path + ".attribute is required");
        }
        for (String value : clause.values()) {
            if (value == null) {
                errors.add(path + ".values must not contain null");
            } else if (op.isNumeric() && !isNumber(value)) {
                errors.add(path + ": '%s' is not a number".formatted(value));
            } else if (op.isSemver() && SemanticVersion.parse(value).isEmpty()) {
                errors.add(path + ": '%s' is not a semantic version".formatted(value));
            } else if (op.isDate() && ClauseMatcher.toInstant(value).isEmpty()) {
                errors.add(path + ": '%s' is not an ISO-8601 timestamp or epoch millis".formatted(value));
            } else if (op == Operator.MATCHES && !isRegex(value)) {
                errors.add(path + ": '%s' is not a valid regular expression".formatted(value));
            }
        }
    }

    private static void validateServe(VariationOrRollout serve, int variationCount, String path, List<String> errors) {
        if (serve == null || (serve.variation() == null) == (serve.rollout() == null)) {
            errors.add(path + ": exactly one of variation or rollout is required");
            return;
        }
        if (serve.variation() != null) {
            checkVariation(serve.variation(), variationCount, path + ".variation", errors);
            return;
        }
        Rollout rollout = serve.rollout();
        if (rollout.variations().isEmpty()) {
            errors.add(path + ".rollout.variations must not be empty");
            return;
        }
        long total = 0;
        Set<Integer> seen = new HashSet<>();
        for (WeightedVariation wv : rollout.variations()) {
            checkVariation(wv.variation(), variationCount, path + ".rollout.variation", errors);
            if (!seen.add(wv.variation())) {
                errors.add(path + ".rollout: variation %d listed more than once".formatted(wv.variation()));
            }
            if (wv.weight() < 0) {
                errors.add(path + ".rollout: weights must not be negative");
            }
            total += wv.weight();
        }
        if (total != Rollout.TOTAL_WEIGHT) {
            errors.add(path + ".rollout: weights must sum to %d (100%%), got %d".formatted(Rollout.TOTAL_WEIGHT, total));
        }
    }

    private static void checkVariation(int index, int count, String path, List<String> errors) {
        if (index < 0 || index >= count) {
            errors.add(path + ": variation index %d is out of range (flag has %d variations)".formatted(index, count));
        }
    }

    private static boolean isNumber(String s) {
        try {
            new BigDecimal(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isRegex(String s) {
        try {
            Pattern.compile(s);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}
