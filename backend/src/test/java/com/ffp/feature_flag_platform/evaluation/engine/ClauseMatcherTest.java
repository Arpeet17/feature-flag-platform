package com.ffp.feature_flag_platform.evaluation.engine;

import com.ffp.feature_flag_platform.evaluation.model.Clause;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationContext;
import com.ffp.feature_flag_platform.evaluation.model.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ClauseMatcherTest {

    static Stream<Arguments> operators() {
        return Stream.of(
                arguments(Operator.IN, "US", List.of("CA", "US"), true),
                arguments(Operator.IN, "DE", List.of("CA", "US"), false),
                arguments(Operator.IN, 42, List.of("42"), true),
                arguments(Operator.IN, 42.0, List.of("42"), true),
                arguments(Operator.IN, true, List.of("true"), true),
                arguments(Operator.STARTS_WITH, "alice@acme.com", List.of("alice"), true),
                arguments(Operator.ENDS_WITH, "alice@acme.com", List.of("@acme.com"), true),
                arguments(Operator.ENDS_WITH, "alice@evil.com", List.of("@acme.com"), false),
                arguments(Operator.CONTAINS, "premium-plan", List.of("mium"), true),
                arguments(Operator.MATCHES, "user-123", List.of("^user-\\d+$"), true),
                arguments(Operator.MATCHES, "admin", List.of("^user-\\d+$"), false),
                arguments(Operator.MATCHES, "x", List.of("[invalid"), false),
                arguments(Operator.LESS_THAN, 17, List.of("18"), true),
                arguments(Operator.LESS_THAN, 18, List.of("18"), false),
                arguments(Operator.LESS_THAN_OR_EQUAL, 18, List.of("18"), true),
                arguments(Operator.GREATER_THAN, "21", List.of("18"), true),
                arguments(Operator.GREATER_THAN, "abc", List.of("18"), false),
                arguments(Operator.GREATER_THAN_OR_EQUAL, 18.5, List.of("18.5"), true),
                arguments(Operator.SEMVER_EQUAL, "2.3.0", List.of("2.3"), true),
                arguments(Operator.SEMVER_LESS_THAN, "2.2.9", List.of("2.3.0"), true),
                arguments(Operator.SEMVER_GREATER_THAN, "2.10.0", List.of("2.9.0"), true),
                arguments(Operator.SEMVER_GREATER_THAN, "not-a-version", List.of("2.9.0"), false),
                arguments(Operator.BEFORE, "2024-01-01T00:00:00Z", List.of("2025-01-01T00:00:00Z"), true),
                arguments(Operator.AFTER, 1735689600000L, List.of("2024-06-01T00:00:00+05:30"), true),
                arguments(Operator.AFTER, "yesterday", List.of("2024-06-01T00:00:00Z"), false)
        );
    }

    @ParameterizedTest(name = "{1} {0} {2} -> {3}")
    @MethodSource("operators")
    void evaluatesOperators(Operator op, Object actual, List<String> values, boolean expected) {
        var context = new EvaluationContext("user", Map.of("attr", actual));
        assertThat(ClauseMatcher.matches(new Clause("attr", op, values, false), context, null)).isEqualTo(expected);
    }

    @Test
    void negationInvertsResult() {
        var context = new EvaluationContext("user", Map.of("country", "US"));
        assertThat(ClauseMatcher.matches(new Clause("country", Operator.IN, List.of("US"), true), context, null)).isFalse();
        assertThat(ClauseMatcher.matches(new Clause("country", Operator.IN, List.of("DE"), true), context, null)).isTrue();
    }

    @Test
    void missingAttributeNeverMatchesEvenWhenNegated() {
        var context = EvaluationContext.of("user");
        assertThat(ClauseMatcher.matches(new Clause("country", Operator.IN, List.of("US"), false), context, null)).isFalse();
        assertThat(ClauseMatcher.matches(new Clause("country", Operator.IN, List.of("US"), true), context, null)).isFalse();
    }

    @Test
    void keyAttributeResolvesToContextKey() {
        assertThat(ClauseMatcher.matches(Clause.of("key", Operator.STARTS_WITH, "user-"), EvaluationContext.of("user-7"), null)).isTrue();
    }

    @Test
    void collectionAttributeMatchesIfAnyElementMatches() {
        var context = new EvaluationContext("user", Map.of("groups", List.of("eng", "beta")));
        assertThat(ClauseMatcher.matches(Clause.of("groups", Operator.IN, "beta"), context, null)).isTrue();
        assertThat(ClauseMatcher.matches(Clause.of("groups", Operator.IN, "sales"), context, null)).isFalse();
    }

    @Test
    void segmentMatchDelegatesToSegmentMatcher() {
        var clause = new Clause(null, Operator.SEGMENT_MATCH, List.of("a", "b"), false);
        assertThat(ClauseMatcher.matches(clause, EvaluationContext.of("u"), "b"::equals)).isTrue();
        assertThat(ClauseMatcher.matches(clause, EvaluationContext.of("u"), "c"::equals)).isFalse();
        assertThat(ClauseMatcher.matches(clause, EvaluationContext.of("u"), null)).isFalse();
    }
}
