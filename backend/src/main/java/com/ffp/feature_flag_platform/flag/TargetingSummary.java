package com.ffp.feature_flag_platform.flag;

import com.ffp.feature_flag_platform.evaluation.model.Rollout;
import com.ffp.feature_flag_platform.evaluation.model.Targeting;
import com.ffp.feature_flag_platform.evaluation.model.Variation;
import com.ffp.feature_flag_platform.evaluation.model.VariationOrRollout;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** One-line, human readable description of a flag's state in an environment (list views, audit log). */
final class TargetingSummary {

    private TargetingSummary() {
    }

    static String describe(boolean enabled, Targeting t, List<Variation> variations) {
        if (!enabled) {
            return "Off · serving " + label(t.offVariation(), variations);
        }
        List<String> parts = new ArrayList<>();
        if (!t.prerequisites().isEmpty()) parts.add(plural(t.prerequisites().size(), "prerequisite"));
        int targeted = t.targets().stream().mapToInt(x -> x.values().size()).sum();
        if (targeted > 0) parts.add(plural(targeted, "target"));
        if (!t.rules().isEmpty()) parts.add(plural(t.rules().size(), "rule"));
        parts.add("default " + describe(t.fallthrough(), variations));
        return "On · " + String.join(", ", parts);
    }

    static String describe(VariationOrRollout serve, List<Variation> variations) {
        if (serve == null) return "?";
        if (serve.variation() != null) return label(serve.variation(), variations);
        return serve.rollout().variations().stream()
                .map(wv -> "%s %s%%".formatted(label(wv.variation(), variations), percent(wv.weight())))
                .collect(Collectors.joining(" / "));
    }

    static String percent(int weight) {
        return BigDecimal.valueOf(weight * 100L)
                .divide(BigDecimal.valueOf(Rollout.TOTAL_WEIGHT))
                .stripTrailingZeros().toPlainString();
    }

    private static String label(int index, List<Variation> variations) {
        if (index < 0 || index >= variations.size()) return "#" + index;
        Variation v = variations.get(index);
        return v.name() != null && !v.name().isBlank() ? v.name() : v.value().toString();
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
