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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TargetingValidatorTest {

    private static List<String> validate(Targeting t) {
        return TargetingValidator.validate("flag", t, 2, Set.of("beta"), Map.of("other", 3)::get);
    }

    @Test
    void acceptsValidTargeting() {
        var t = new Targeting(1,
                List.of(new Prerequisite("other", 2)),
                List.of(new Target(0, Set.of("alice"))),
                List.of(new Rule("r1", null, List.of(new Clause(null, Operator.SEGMENT_MATCH, List.of("beta"), false),
                        Clause.of("version", Operator.SEMVER_GREATER_THAN, "1.2.0"),
                        Clause.of("signup", Operator.AFTER, "2024-01-01T00:00:00Z"),
                        Clause.of("email", Operator.MATCHES, ".*@acme\\.com")), VariationOrRollout.fixed(0))),
                VariationOrRollout.rollout(new Rollout(List.of(new WeightedVariation(0, 25_000), new WeightedVariation(1, 75_000)), null)));
        assertThat(validate(t)).isEmpty();
    }

    @Test
    void reportsEveryProblemWithItsLocation() {
        var t = new Targeting(5,
                List.of(new Prerequisite("flag", 0), new Prerequisite("ghost", 0), new Prerequisite("other", 7)),
                List.of(new Target(0, Set.of("alice")), new Target(1, Set.of("alice"))),
                List.of(new Rule("r", null, List.of(), VariationOrRollout.fixed(0)),
                        new Rule("r", null, List.of(Clause.of("age", Operator.LESS_THAN, "old"),
                                new Clause(null, Operator.SEGMENT_MATCH, List.of("nope"), false),
                                Clause.of("v", Operator.SEMVER_EQUAL, "x.y"),
                                Clause.of("d", Operator.BEFORE, "tomorrow"),
                                Clause.of("e", Operator.MATCHES, "[")), VariationOrRollout.fixed(3))),
                VariationOrRollout.rollout(new Rollout(List.of(new WeightedVariation(0, 60_000), new WeightedVariation(0, 60_000)), null)));

        assertThat(validate(t)).containsExactlyInAnyOrder(
                "offVariation: variation index 5 is out of range (flag has 2 variations)",
                "prerequisites[0]: a flag cannot be its own prerequisite",
                "prerequisites[1]: flag 'ghost' does not exist or is archived",
                "prerequisites[2].variation: variation index 7 is out of range (flag has 3 variations)",
                "targets[1]: context 'alice' is targeted more than once",
                "rules[0]: a rule needs at least one clause",
                "rules[1]: duplicate rule id 'r'",
                "rules[1].clauses[0]: 'old' is not a number",
                "rules[1].clauses[1]: segment 'nope' does not exist",
                "rules[1].clauses[2]: 'x.y' is not a semantic version",
                "rules[1].clauses[3]: 'tomorrow' is not an ISO-8601 timestamp or epoch millis",
                "rules[1].clauses[4]: '[' is not a valid regular expression",
                "rules[1].serve.variation: variation index 3 is out of range (flag has 2 variations)",
                "fallthrough.rollout: variation 0 listed more than once",
                "fallthrough.rollout: weights must sum to 100000 (100%), got 120000");
    }

    @Test
    void serveNeedsExactlyOneOfVariationOrRollout() {
        var both = new VariationOrRollout(0, new Rollout(List.of(new WeightedVariation(0, 100_000)), null));
        assertThat(validate(new Targeting(0, null, null, null, both)))
                .containsExactly("fallthrough: exactly one of variation or rollout is required");
        assertThat(validate(new Targeting(0, null, null, null, null)))
                .containsExactly("fallthrough: exactly one of variation or rollout is required");
    }

    @Test
    void segmentRulesCannotNestSegments() {
        var errors = TargetingValidator.validateSegmentRules(List.of(
                new SegmentRule(List.of(new Clause(null, Operator.SEGMENT_MATCH, List.of("x"), false))),
                new SegmentRule(List.of(Clause.of("", Operator.IN, "a")))));
        assertThat(errors).containsExactly(
                "rules[0].clauses[0]: segments cannot reference other segments",
                "rules[1].clauses[0].attribute is required");
    }
}
