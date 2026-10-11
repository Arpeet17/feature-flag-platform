package com.ffp.feature_flag_platform.evaluation.engine;

import com.ffp.feature_flag_platform.evaluation.model.Clause;
import com.ffp.feature_flag_platform.evaluation.model.EvaluationContext;
import com.ffp.feature_flag_platform.evaluation.model.Operator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates a single clause against a context. Type mismatches (e.g. GREATER_THAN on a
 * non-numeric attribute) never throw – they simply don't match, because a bad attribute
 * from one client must not break evaluation for everyone.
 */
final class ClauseMatcher {

    private static final int MAX_CACHED_PATTERNS = 1_000;
    private static final Map<String, Optional<Pattern>> PATTERN_CACHE = new ConcurrentHashMap<>();

    private ClauseMatcher() {
    }

    /**
     * @param segmentMatcher resolves SEGMENT_MATCH clauses; may be null where segment
     *                       references are not allowed (inside segment rules).
     */
    static boolean matches(Clause clause, EvaluationContext context, Predicate<String> segmentMatcher) {
        boolean result;
        if (clause.operator() == Operator.SEGMENT_MATCH) {
            result = segmentMatcher != null && clause.values().stream().anyMatch(segmentMatcher);
        } else {
            Object attribute = context.attribute(clause.attribute());
            if (attribute == null) {
                // Missing attributes never match, even for negated clauses: "country NOT IN [US]"
                // should not silently target users whose country is unknown.
                return false;
            }
            result = attribute instanceof Collection<?> collection
                    ? collection.stream().anyMatch(v -> v != null && matchesAnyValue(clause, v))
                    : matchesAnyValue(clause, attribute);
        }
        return clause.negate() != result;
    }

    private static boolean matchesAnyValue(Clause clause, Object attribute) {
        for (String expected : clause.values()) {
            if (matchesValue(clause.operator(), attribute, expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesValue(Operator op, Object actual, String expected) {
        return switch (op) {
            case IN -> equalsLoosely(actual, expected);
            case STARTS_WITH -> actual instanceof String s && s.startsWith(expected);
            case ENDS_WITH -> actual instanceof String s && s.endsWith(expected);
            case CONTAINS -> actual instanceof String s && s.contains(expected);
            case MATCHES -> actual instanceof String s && pattern(expected).map(p -> p.matcher(s).find()).orElse(false);
            case LESS_THAN -> compareNumbers(actual, expected).map(c -> c < 0).orElse(false);
            case LESS_THAN_OR_EQUAL -> compareNumbers(actual, expected).map(c -> c <= 0).orElse(false);
            case GREATER_THAN -> compareNumbers(actual, expected).map(c -> c > 0).orElse(false);
            case GREATER_THAN_OR_EQUAL -> compareNumbers(actual, expected).map(c -> c >= 0).orElse(false);
            case SEMVER_EQUAL -> compareSemver(actual, expected).map(c -> c == 0).orElse(false);
            case SEMVER_LESS_THAN -> compareSemver(actual, expected).map(c -> c < 0).orElse(false);
            case SEMVER_GREATER_THAN -> compareSemver(actual, expected).map(c -> c > 0).orElse(false);
            case BEFORE -> compareDates(actual, expected).map(c -> c < 0).orElse(false);
            case AFTER -> compareDates(actual, expected).map(c -> c > 0).orElse(false);
            case SEGMENT_MATCH -> false;
        };
    }

    /** Clause values are strings; compare numbers numerically and everything else textually. */
    private static boolean equalsLoosely(Object actual, String expected) {
        if (actual instanceof Number) {
            return compareNumbers(actual, expected).map(c -> c == 0).orElse(false);
        }
        return String.valueOf(actual).equals(expected);
    }

    private static Optional<Integer> compareNumbers(Object actual, String expected) {
        Optional<BigDecimal> a = toDecimal(actual);
        Optional<BigDecimal> e = toDecimal(expected);
        return a.isPresent() && e.isPresent() ? Optional.of(a.get().compareTo(e.get())) : Optional.empty();
    }

    private static Optional<BigDecimal> toDecimal(Object value) {
        try {
            if (value instanceof Number n) {
                return Optional.of(new BigDecimal(n.toString()));
            }
            if (value instanceof String s && !s.isBlank()) {
                return Optional.of(new BigDecimal(s.trim()));
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return Optional.empty();
    }

    private static Optional<Integer> compareSemver(Object actual, String expected) {
        if (!(actual instanceof String s)) {
            return Optional.empty();
        }
        var a = SemanticVersion.parse(s);
        var e = SemanticVersion.parse(expected);
        return a.isPresent() && e.isPresent() ? Optional.of(a.get().compareTo(e.get())) : Optional.empty();
    }

    private static Optional<Integer> compareDates(Object actual, String expected) {
        var a = toInstant(actual);
        var e = toInstant(expected);
        return a.isPresent() && e.isPresent() ? Optional.of(a.get().compareTo(e.get())) : Optional.empty();
    }

    /** Accepts epoch milliseconds or ISO-8601 timestamps (with offset). */
    static Optional<Instant> toInstant(Object value) {
        if (value instanceof Number n) {
            return Optional.of(Instant.ofEpochMilli(n.longValue()));
        }
        if (value instanceof String s) {
            try {
                return Optional.of(OffsetDateTime.parse(s.trim()).toInstant());
            } catch (DateTimeParseException e) {
                try {
                    return Optional.of(Instant.ofEpochMilli(Long.parseLong(s.trim())));
                } catch (NumberFormatException ignored) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Pattern> pattern(String regex) {
        if (PATTERN_CACHE.size() > MAX_CACHED_PATTERNS) {
            PATTERN_CACHE.clear();
        }
        return PATTERN_CACHE.computeIfAbsent(regex, r -> {
            try {
                return Optional.of(Pattern.compile(r));
            } catch (PatternSyntaxException e) {
                return Optional.empty();
            }
        });
    }
}
