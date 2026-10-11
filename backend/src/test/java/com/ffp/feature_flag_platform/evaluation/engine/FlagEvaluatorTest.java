package com.ffp.feature_flag_platform.evaluation.engine;

import com.ffp.feature_flag_platform.evaluation.model.Clause;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationContext;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationReason.ErrorKind;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationReason.Kind;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationResult;
import com.ffp.feature_flag_platform.evaluation.model.FlagDefinition;
import com.ffp.feature_flag_platform.evaluation.model.FlagType;
import com.ffp.feature_flag_platform.evaluation.model.Operator;
import com.ffp.feature_flag_platform.evaluation.model.Prerequisite;
import com.ffp.feature_flag_platform.evaluation.model.Rollout;
import com.ffp.feature_flag_platform.evaluation.model.Rule;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition;
import com.ffp.feature_flag_platform.evaluation.model.SegmentDefinition.SegmentRule;
import com.ffp.feature_flag_platform.evaluation.model.Target;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.model.Variation;
import com.ffp.feature_flag_platform.evaluation.model.VariationOrRollout;
import com.ffp.feature_flag_platform.evaluation.model.WeightedVariation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FlagEvaluatorTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final List<Variation> BOOL = List.of(
            new Variation(JSON.booleanNode(true), "on", null), new Variation(JSON.booleanNode(false), "off", null));

    private final Map<String, FlagDefinition> flags = new HashMap<>();
    private final Map<String, SegmentDefinition> segments = new HashMap<>();
    private final FlagEvaluator evaluator = new FlagEvaluator(flags::get, segments::get);

    private static FlagDefinition flag(String key, boolean enabled, Targeting targeting) {
        return new FlagDefinition(key, FlagType.BOOLEAN, BOOL, enabled, targeting, "salt-" + key, 7);
    }

    private static Targeting targeting(List<Prerequisite> prereqs, List<Target> targets, List<Rule> rules, VariationOrRollout fallthrough) {
        return new Targeting(1, prereqs, targets, rules, fallthrough);
    }

    private static Rule rule(String id, VariationOrRollout serve, Clause... clauses) {
        return new Rule(id, null, List.of(clauses), serve);
    }

    private EvaluationResult eval(FlagDefinition flag, EvaluationContext ctx) {
        flags.put(flag.key(), flag);
        return evaluator.evaluate(flag.key(), ctx);
    }

    @Test
    void offFlagServesOffVariation() {
        var result = eval(flag("f", false, Targeting.simple(0, 1)), EvaluationContext.of("u"));
        assertThat(result.variationIndex()).isEqualTo(1);
        assertThat(result.value().booleanValue()).isFalse();
        assertThat(result.reason().kind()).isEqualTo(Kind.OFF);
        assertThat(result.flagVersion()).isEqualTo(7);
    }

    @Test
    void unknownFlagIsAnError() {
        var result = evaluator.evaluate("missing", EvaluationContext.of("u"));
        assertThat(result.isError()).isTrue();
        assertThat(result.reason().errorKind()).isEqualTo(ErrorKind.FLAG_NOT_FOUND);
        assertThat(result.variationIndex()).isNull();
    }

    @Test
    void fallthroughWhenNothingElseMatches() {
        var result = eval(flag("f", true, Targeting.simple(0, 1)), EvaluationContext.of("u"));
        assertThat(result.variationIndex()).isZero();
        assertThat(result.reason().kind()).isEqualTo(Kind.FALLTHROUGH);
        assertThat(result.reason().inRollout()).isNull();
    }

    @Nested
    class Precedence {

        private final FlagDefinition flag = flag("f", true, targeting(
                List.of(),
                List.of(new Target(1, Set.of("blocked-user"))),
                List.of(rule("r0", VariationOrRollout.fixed(1), Clause.of("country", Operator.IN, "DE")),
                        rule("r1", VariationOrRollout.fixed(0), Clause.of("plan", Operator.IN, "pro"))),
                VariationOrRollout.fixed(1)));

        @Test
        void individualTargetBeatsRules() {
            var result = eval(flag, new EvaluationContext("blocked-user", Map.of("plan", "pro")));
            assertThat(result.reason().kind()).isEqualTo(Kind.TARGET_MATCH);
            assertThat(result.variationIndex()).isEqualTo(1);
        }

        @Test
        void firstMatchingRuleWins() {
            var result = eval(flag, new EvaluationContext("u", Map.of("country", "DE", "plan", "pro")));
            assertThat(result.reason().kind()).isEqualTo(Kind.RULE_MATCH);
            assertThat(result.reason().ruleIndex()).isZero();
            assertThat(result.reason().ruleId()).isEqualTo("r0");
        }

        @Test
        void laterRuleMatchesWhenEarlierDoesNot() {
            var result = eval(flag, new EvaluationContext("u", Map.of("country", "US", "plan", "pro")));
            assertThat(result.reason().ruleIndex()).isEqualTo(1);
            assertThat(result.variationIndex()).isZero();
        }

        @Test
        void allClausesOfARuleMustMatch() {
            var f = flag("g", true, targeting(List.of(), List.of(), List.of(rule("r", VariationOrRollout.fixed(0),
                    Clause.of("country", Operator.IN, "US"), Clause.of("age", Operator.GREATER_THAN_OR_EQUAL, "18"))),
                    VariationOrRollout.fixed(1)));
            assertThat(eval(f, new EvaluationContext("u", Map.of("country", "US", "age", 17))).reason().kind()).isEqualTo(Kind.FALLTHROUGH);
            assertThat(eval(f, new EvaluationContext("u", Map.of("country", "US", "age", 30))).reason().kind()).isEqualTo(Kind.RULE_MATCH);
        }
    }

    @Nested
    class Rollouts {

        @Test
        void percentageRolloutIsAccurateAndSticky() {
            var f = flag("rollout", true, targeting(List.of(), List.of(), List.of(), VariationOrRollout.rollout(
                    new Rollout(List.of(new WeightedVariation(0, 30_000), new WeightedVariation(1, 70_000)), null))));
            flags.put(f.key(), f);
            long on = IntStream.range(0, 50_000)
                    .filter(i -> evaluator.evaluate("rollout", EvaluationContext.of("user-" + i)).variationIndex() == 0)
                    .count();
            assertThat(on / 50_000.0).isCloseTo(0.30, within(0.01));

            var first = evaluator.evaluate("rollout", EvaluationContext.of("user-123"));
            assertThat(first.reason().inRollout()).isTrue();
            for (int i = 0; i < 10; i++) {
                assertThat(evaluator.evaluate("rollout", EvaluationContext.of("user-123")).variationIndex())
                        .isEqualTo(first.variationIndex());
            }
        }

        @Test
        void bucketByCustomAttributeKeepsGroupsTogether() {
            var f = flag("org-rollout", true, targeting(List.of(), List.of(), List.of(), VariationOrRollout.rollout(
                    new Rollout(List.of(new WeightedVariation(0, 50_000), new WeightedVariation(1, 50_000)), "orgId"))));
            flags.put(f.key(), f);
            var variations = IntStream.range(0, 100)
                    .mapToObj(i -> evaluator.evaluate("org-rollout", new EvaluationContext("user-" + i, Map.of("orgId", "org-42"))).variationIndex())
                    .distinct().toList();
            assertThat(variations).hasSize(1);
        }

        @Test
        void zeroAndFullRollouts() {
            var none = flag("none", true, targeting(List.of(), List.of(), List.of(), VariationOrRollout.rollout(
                    new Rollout(List.of(new WeightedVariation(0, 0), new WeightedVariation(1, 100_000)), null))));
            var all = flag("all", true, targeting(List.of(), List.of(), List.of(), VariationOrRollout.rollout(
                    new Rollout(List.of(new WeightedVariation(0, 100_000), new WeightedVariation(1, 0)), null))));
            IntStream.range(0, 1000).forEach(i -> {
                assertThat(eval(none, EvaluationContext.of("u" + i)).variationIndex()).isEqualTo(1);
                assertThat(eval(all, EvaluationContext.of("u" + i)).variationIndex()).isEqualTo(0);
            });
        }
    }

    @Nested
    class Prerequisites {

        @Test
        void servesOffVariationWhenPrerequisiteNotMet() {
            flags.put("parent", flag("parent", true, Targeting.simple(1, 1)));
            var child = flag("child", true, targeting(List.of(new Prerequisite("parent", 0)), List.of(), List.of(), VariationOrRollout.fixed(0)));
            var result = eval(child, EvaluationContext.of("u"));
            assertThat(result.reason().kind()).isEqualTo(Kind.PREREQUISITE_FAILED);
            assertThat(result.reason().prerequisiteKey()).isEqualTo("parent");
            assertThat(result.variationIndex()).isEqualTo(1);
        }

        @Test
        void evaluatesNormallyWhenPrerequisiteMet() {
            flags.put("parent", flag("parent", true, Targeting.simple(0, 1)));
            var child = flag("child", true, targeting(List.of(new Prerequisite("parent", 0)), List.of(), List.of(), VariationOrRollout.fixed(0)));
            assertThat(eval(child, EvaluationContext.of("u")).reason().kind()).isEqualTo(Kind.FALLTHROUGH);
        }

        @Test
        void disabledPrerequisiteFailsEvenIfOffVariationMatches() {
            flags.put("parent", flag("parent", false, Targeting.simple(0, 0)));
            var child = flag("child", true, targeting(List.of(new Prerequisite("parent", 0)), List.of(), List.of(), VariationOrRollout.fixed(0)));
            assertThat(eval(child, EvaluationContext.of("u")).reason().kind()).isEqualTo(Kind.PREREQUISITE_FAILED);
        }

        @Test
        void cyclesDoNotOverflowTheStack() {
            flags.put("a", flag("a", true, targeting(List.of(new Prerequisite("b", 0)), List.of(), List.of(), VariationOrRollout.fixed(0))));
            flags.put("b", flag("b", true, targeting(List.of(new Prerequisite("a", 0)), List.of(), List.of(), VariationOrRollout.fixed(0))));
            var result = evaluator.evaluate("a", EvaluationContext.of("u"));
            assertThat(result.reason().kind()).isEqualTo(Kind.PREREQUISITE_FAILED);
        }
    }

    @Nested
    class Segments {

        private final Clause inBeta = new Clause(null, Operator.SEGMENT_MATCH, List.of("beta"), false);
        private final FlagDefinition flag = flag("f", true, targeting(List.of(), List.of(),
                List.of(rule("r", VariationOrRollout.fixed(0), inBeta)), VariationOrRollout.fixed(1)));

        @Test
        void includedExcludedAndRules() {
            segments.put("beta", new SegmentDefinition("beta", Set.of("alice"), Set.of("mallory@acme.com"),
                    List.of(new SegmentRule(List.of(Clause.of("email", Operator.ENDS_WITH, "@acme.com"))))));

            assertThat(eval(flag, EvaluationContext.of("alice")).variationIndex()).isZero();
            assertThat(eval(flag, new EvaluationContext("bob@acme.com", Map.of("email", "bob@acme.com"))).variationIndex()).isZero();
            assertThat(eval(flag, new EvaluationContext("mallory@acme.com", Map.of("email", "mallory@acme.com"))).variationIndex()).isEqualTo(1);
            assertThat(eval(flag, new EvaluationContext("eve", Map.of("email", "eve@evil.com"))).variationIndex()).isEqualTo(1);
        }

        @Test
        void unknownSegmentDoesNotMatch() {
            assertThat(eval(flag, EvaluationContext.of("alice")).reason().kind()).isEqualTo(Kind.FALLTHROUGH);
        }
    }

    @Test
    void outOfRangeVariationIsMalformed() {
        var result = eval(flag("f", true, Targeting.simple(9, 1)), EvaluationContext.of("u"));
        assertThat(result.isError()).isTrue();
        assertThat(result.reason().errorKind()).isEqualTo(ErrorKind.MALFORMED_FLAG);
    }
}
