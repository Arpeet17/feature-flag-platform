package com.ffp.feature_flag_platform.experiment.stats;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Frequentist analysis of an A/B/n experiment, comparing every treatment against control.
 *
 * <ul>
 *   <li><b>Conversion metrics</b>: Wilson score intervals per arm (well behaved near 0% and with
 *       small samples, unlike the Wald interval), pooled two-proportion z-test, unpooled
 *       interval for the difference.</li>
 *   <li><b>Mean metrics</b> (e.g. revenue per user): Welch's t-test, which does not assume equal
 *       variances, with Welch–Satterthwaite degrees of freedom.</li>
 *   <li><b>Multiple comparisons</b>: with k treatments each is tested at alpha/k (Bonferroni),
 *       keeping the family-wise false-positive rate at alpha.</li>
 *   <li><b>Sample ratio mismatch</b>: a chi-square goodness-of-fit test of observed users per arm
 *       against the configured split. A mismatch means assignment or logging is broken and the
 *       results cannot be trusted, however significant they look.</li>
 *   <li><b>Power</b>: for inconclusive results, the users per arm needed to detect the observed
 *       effect with 80% power, so "keep running" comes with a number.</li>
 * </ul>
 */
public final class ExperimentAnalyzer {

    public static final double SRM_THRESHOLD = 0.001;
    public static final long MIN_USERS_PER_ARM = 100;
    public static final long MIN_CONVERSIONS_PER_ARM = 5;
    static final double POWER = 0.8;

    private ExperimentAnalyzer() {
    }

    public enum MetricType { CONVERSION, MEAN }

    public enum Verdict { BETTER, WORSE, INCONCLUSIVE, INSUFFICIENT_DATA }

    /** Raw aggregates for one variation. For CONVERSION metrics {@code sum}/{@code sumSquares} are unused. */
    public record ArmData(int variation, String label, long users, long conversions, double sum, double sumSquares) {
    }

    public record ArmResult(int variation, String label, long users, long conversions, double estimate,
                            double ciLow, double ciHigh) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Comparison(int variation, String label, double absoluteLift, Double relativeLift, double ciLow, double ciHigh,
                             double pValue, boolean significant, Verdict verdict, Long usersNeededPerArm) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SampleRatioCheck(boolean checked, Double pValue, boolean mismatch,
                                   Map<Integer, Double> expectedShare, Map<Integer, Double> observedShare) {
    }

    public record Analysis(MetricType metricType, boolean higherIsBetter, int controlVariation, double alpha,
                           double adjustedAlpha, List<ArmResult> arms, List<Comparison> comparisons,
                           SampleRatioCheck sampleRatio, List<String> warnings) {
    }

    /**
     * @param expectedWeights configured traffic split (variation → weight, any scale); empty if the
     *                        flag does not use a percentage rollout, in which case SRM can't be checked
     */
    public static Analysis analyze(List<ArmData> arms, int controlVariation, MetricType type, boolean higherIsBetter,
                                   double alpha, Map<Integer, Integer> expectedWeights) {
        ArmData control = arms.stream().filter(a -> a.variation() == controlVariation).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("control variation has no arm"));
        List<ArmData> treatments = arms.stream().filter(a -> a.variation() != controlVariation).toList();
        double adjustedAlpha = treatments.isEmpty() ? alpha : alpha / treatments.size();
        List<String> warnings = new ArrayList<>();

        List<ArmResult> armResults = arms.stream().map(a -> armResult(a, type, alpha)).toList();
        List<Comparison> comparisons = new ArrayList<>();
        for (ArmData t : treatments) {
            comparisons.add(type == MetricType.CONVERSION
                    ? compareProportions(control, t, adjustedAlpha, higherIsBetter)
                    : compareMeans(control, t, adjustedAlpha, higherIsBetter));
        }
        if (comparisons.stream().anyMatch(c -> c.verdict() == Verdict.INSUFFICIENT_DATA)) {
            warnings.add("Not enough data yet: each variation needs at least %d users%s before results are reported."
                    .formatted(MIN_USERS_PER_ARM, type == MetricType.CONVERSION
                            ? " and %d conversions".formatted(MIN_CONVERSIONS_PER_ARM) : ""));
        }
        if (treatments.size() > 1) {
            warnings.add("%d treatments are compared against control, so each is tested at α = %s (Bonferroni)."
                    .formatted(treatments.size(), round(adjustedAlpha)));
        }

        SampleRatioCheck srm = sampleRatio(arms, expectedWeights);
        if (!srm.checked()) {
            warnings.add("The flag doesn't split traffic with a percentage rollout, so sample ratio can't be checked.");
        } else if (srm.mismatch()) {
            warnings.add("Sample ratio mismatch (p = %s): users are not split as configured. Assignment or event "
                    .formatted(String.format("%.2g", srm.pValue()))
                    + "logging is likely broken; do not trust these results until it is explained.");
        }
        return new Analysis(type, higherIsBetter, controlVariation, alpha, adjustedAlpha, armResults, comparisons, srm, warnings);
    }

    // ------------------------------------------------------------------ per arm

    private static ArmResult armResult(ArmData a, MetricType type, double alpha) {
        if (a.users() == 0) {
            return new ArmResult(a.variation(), a.label(), 0, a.conversions(), 0, 0, 0);
        }
        double z = Distributions.normalQuantile(1 - alpha / 2);
        if (type == MetricType.CONVERSION) {
            double[] ci = wilson(a.conversions(), a.users(), z);
            return new ArmResult(a.variation(), a.label(), a.users(), a.conversions(),
                    (double) a.conversions() / a.users(), ci[0], ci[1]);
        }
        double mean = a.sum() / a.users();
        double half = a.users() > 1 ? tQuantile(alpha, a.users() - 1) * Math.sqrt(variance(a) / a.users()) : 0;
        return new ArmResult(a.variation(), a.label(), a.users(), a.conversions(), mean, mean - half, mean + half);
    }

    /** Wilson score interval for a binomial proportion. */
    static double[] wilson(long successes, long n, double z) {
        double p = (double) successes / n;
        double z2 = z * z;
        double denominator = 1 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denominator;
        double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denominator;
        return new double[] {Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    // ------------------------------------------------------------------ comparisons

    static Comparison compareProportions(ArmData c, ArmData t, double alpha, boolean higherIsBetter) {
        if (c.users() < MIN_USERS_PER_ARM || t.users() < MIN_USERS_PER_ARM
                || c.conversions() < MIN_CONVERSIONS_PER_ARM || t.conversions() < MIN_CONVERSIONS_PER_ARM) {
            return insufficient(t, c);
        }
        double pc = (double) c.conversions() / c.users();
        double pt = (double) t.conversions() / t.users();
        double diff = pt - pc;
        double pooled = (double) (c.conversions() + t.conversions()) / (c.users() + t.users());
        double sePooled = Math.sqrt(pooled * (1 - pooled) * (1.0 / c.users() + 1.0 / t.users()));
        double pValue = sePooled == 0 ? 1 : Distributions.normalTwoSidedP(diff / sePooled);
        double seDiff = Math.sqrt(pt * (1 - pt) / t.users() + pc * (1 - pc) / c.users());
        double z = Distributions.normalQuantile(1 - alpha / 2);
        Long needed = pValue < alpha || diff == 0 ? null
                : usersNeeded(pt * (1 - pt) + pc * (1 - pc), diff, alpha);
        return comparison(t, diff, pc == 0 ? null : diff / pc, diff - z * seDiff, diff + z * seDiff, pValue, alpha,
                higherIsBetter, needed);
    }

    static Comparison compareMeans(ArmData c, ArmData t, double alpha, boolean higherIsBetter) {
        if (c.users() < MIN_USERS_PER_ARM || t.users() < MIN_USERS_PER_ARM) {
            return insufficient(t, c);
        }
        double mc = c.sum() / c.users();
        double mt = t.sum() / t.users();
        double vc = variance(c);
        double vt = variance(t);
        double diff = mt - mc;
        double se2c = vc / c.users();
        double se2t = vt / t.users();
        double se = Math.sqrt(se2c + se2t);
        double df = se == 0 ? Double.POSITIVE_INFINITY
                : (se2c + se2t) * (se2c + se2t) / (se2c * se2c / (c.users() - 1) + se2t * se2t / (t.users() - 1));
        double pValue = se == 0 ? (diff == 0 ? 1 : 0) : Distributions.studentTTwoSidedP(diff / se, df);
        double half = tQuantile(alpha, df) * se;
        Long needed = pValue < alpha || diff == 0 ? null : usersNeeded(vc + vt, diff, alpha);
        return comparison(t, diff, mc == 0 ? null : diff / mc, diff - half, diff + half, pValue, alpha, higherIsBetter, needed);
    }

    private static Comparison comparison(ArmData t, double diff, Double relative, double low, double high, double pValue,
                                         double alpha, boolean higherIsBetter, Long needed) {
        boolean significant = pValue < alpha;
        Verdict verdict = !significant ? Verdict.INCONCLUSIVE
                : (diff > 0) == higherIsBetter ? Verdict.BETTER : Verdict.WORSE;
        return new Comparison(t.variation(), t.label(), diff, relative, low, high, pValue, significant, verdict, needed);
    }

    private static Comparison insufficient(ArmData t, ArmData c) {
        return new Comparison(t.variation(), t.label(), 0, null, 0, 0, 1, false, Verdict.INSUFFICIENT_DATA, null);
    }

    /** Users per arm for a two-sided test at alpha with {@link #POWER} power: n = (z_α/2 + z_β)² · Σσ² / δ². */
    static long usersNeeded(double varianceSum, double effect, double alpha) {
        double z = Distributions.normalQuantile(1 - alpha / 2) + Distributions.normalQuantile(POWER);
        double n = z * z * varianceSum / (effect * effect);
        return n > 1e12 ? Long.MAX_VALUE : (long) Math.ceil(n);
    }

    private static double variance(ArmData a) {
        if (a.users() < 2) return 0;
        double mean = a.sum() / a.users();
        return Math.max(0, (a.sumSquares() - a.users() * mean * mean) / (a.users() - 1));
    }

    /** Two-sided Student t critical value, found by bisection on the CDF. */
    static double tQuantile(double alpha, double df) {
        if (Double.isInfinite(df) || df > 1e7) {
            return Distributions.normalQuantile(1 - alpha / 2);
        }
        double lo = 0;
        double hi = 1000;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (Distributions.studentTTwoSidedP(mid, df) > alpha) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    // ------------------------------------------------------------------ sample ratio mismatch

    static SampleRatioCheck sampleRatio(List<ArmData> arms, Map<Integer, Integer> expectedWeights) {
        long total = arms.stream().mapToLong(ArmData::users).sum();
        double weightSum = expectedWeights.values().stream().mapToDouble(Integer::doubleValue).sum();
        Map<Integer, Double> observed = new LinkedHashMap<>();
        arms.forEach(a -> observed.put(a.variation(), total == 0 ? 0 : (double) a.users() / total));
        if (expectedWeights.isEmpty() || weightSum == 0) {
            return new SampleRatioCheck(false, null, false, null, observed);
        }
        Map<Integer, Double> expected = new LinkedHashMap<>();
        expectedWeights.forEach((v, w) -> expected.put(v, w / weightSum));
        if (total == 0) {
            return new SampleRatioCheck(true, 1.0, false, expected, observed);
        }
        double chi2 = 0;
        int categories = 0;
        boolean impossible = false;
        for (ArmData a : arms) {
            double share = expected.getOrDefault(a.variation(), 0.0);
            if (share == 0) {
                impossible |= a.users() > 0; // users in a variation that should receive no traffic
                continue;
            }
            double e = share * total;
            chi2 += (a.users() - e) * (a.users() - e) / e;
            categories++;
        }
        double p = impossible ? 0 : categories < 2 ? 1 : Distributions.chiSquareSurvival(chi2, categories - 1);
        return new SampleRatioCheck(true, p, p < SRM_THRESHOLD, expected, observed);
    }

    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
