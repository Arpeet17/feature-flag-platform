package com.ffp.feature_flag_platform.experiment.stats;

import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.ArmData;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.MetricType;
import com.ffp.feature_flag_platform.experiment.stats.ExperimentAnalyzer.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Expected values computed with statsmodels (proportions_ztest, proportion_confint) and SciPy. */
class ExperimentAnalyzerTest {

    private static ArmData conversions(int variation, long users, long conversions) {
        return new ArmData(variation, "v" + variation, users, conversions, 0, 0);
    }

    /** Builds a mean-metric arm from summary statistics (mean, standard deviation). */
    private static ArmData means(int variation, long n, double mean, double sd) {
        return new ArmData(variation, "v" + variation, n, n, mean * n, (n - 1) * sd * sd + n * mean * mean);
    }

    @Test
    void conversionTestMatchesStatsmodels() {
        var analysis = ExperimentAnalyzer.analyze(List.of(conversions(0, 1000, 100), conversions(1, 1000, 130)),
                0, MetricType.CONVERSION, true, 0.05, Map.of(0, 50_000, 1, 50_000));

        var control = analysis.arms().getFirst();
        assertThat(control.estimate()).isEqualTo(0.1);
        assertThat(control.ciLow()).isCloseTo(0.08290944359309571, within(1e-12));
        assertThat(control.ciHigh()).isCloseTo(0.1201519631953484, within(1e-12));

        var cmp = analysis.comparisons().getFirst();
        assertThat(cmp.pValue()).isCloseTo(0.03548845046647471, within(1e-10));
        assertThat(cmp.absoluteLift()).isCloseTo(0.03, within(1e-12));
        assertThat(cmp.relativeLift()).isCloseTo(0.3, within(1e-12));
        assertThat(cmp.ciLow()).isCloseTo(0.0020679344393763656, within(1e-10));
        assertThat(cmp.ciHigh()).isCloseTo(0.057932065560623636, within(1e-10));
        assertThat(cmp.verdict()).isEqualTo(Verdict.BETTER);
        assertThat(cmp.usersNeededPerArm()).isNull();
        assertThat(analysis.sampleRatio().mismatch()).isFalse();
        assertThat(analysis.warnings()).isEmpty();
    }

    @Test
    void lowerIsBetterFlipsTheVerdict() {
        var analysis = ExperimentAnalyzer.analyze(List.of(conversions(0, 1000, 100), conversions(1, 1000, 130)),
                0, MetricType.CONVERSION, false, 0.05, Map.of());
        assertThat(analysis.comparisons().getFirst().verdict()).isEqualTo(Verdict.WORSE);
    }

    @Test
    void wilsonIntervalHandlesZeroConversions() {
        double[] ci = ExperimentAnalyzer.wilson(0, 150, Distributions.normalQuantile(0.975));
        assertThat(ci[0]).isZero();
        assertThat(ci[1]).isCloseTo(0.02497024436807661, within(1e-12));
    }

    @Test
    void welchTestMatchesScipy() {
        var analysis = ExperimentAnalyzer.analyze(List.of(means(0, 500, 20, 8), means(1, 480, 21.5, 9)),
                0, MetricType.MEAN, true, 0.05, Map.of());
        var cmp = analysis.comparisons().getFirst();
        assertThat(cmp.absoluteLift()).isCloseTo(1.5, within(1e-9));
        assertThat(cmp.pValue()).isCloseTo(0.006006931534408224, within(1e-9));
        assertThat(cmp.ciLow()).isCloseTo(0.4309582754582906, within(1e-6));
        assertThat(cmp.ciHigh()).isCloseTo(2.5690417245417096, within(1e-6));
        assertThat(cmp.verdict()).isEqualTo(Verdict.BETTER);
    }

    @Test
    void inconclusiveResultsSayHowManyUsersAreNeeded() {
        // 10% vs 12% with only 500 users each: not significant; ~3,839 per arm would detect it.
        var analysis = ExperimentAnalyzer.analyze(List.of(conversions(0, 500, 50), conversions(1, 500, 60)),
                0, MetricType.CONVERSION, true, 0.05, Map.of());
        var cmp = analysis.comparisons().getFirst();
        assertThat(cmp.verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(cmp.usersNeededPerArm()).isEqualTo(3839L); // ceil(3838.10…)
    }

    @Test
    void smallSamplesAreNotJudged() {
        var analysis = ExperimentAnalyzer.analyze(List.of(conversions(0, 80, 2), conversions(1, 90, 30)),
                0, MetricType.CONVERSION, true, 0.05, Map.of());
        assertThat(analysis.comparisons().getFirst().verdict()).isEqualTo(Verdict.INSUFFICIENT_DATA);
        assertThat(analysis.warnings()).anyMatch(w -> w.startsWith("Not enough data"));
    }

    @Test
    void bonferroniCorrectionAppliesWithSeveralTreatments() {
        // p ≈ 0.035 would win alone, but not at α/2 = 0.025 with two treatments.
        var analysis = ExperimentAnalyzer.analyze(
                List.of(conversions(0, 1000, 100), conversions(1, 1000, 130), conversions(2, 1000, 101)),
                0, MetricType.CONVERSION, true, 0.05, Map.of());
        assertThat(analysis.adjustedAlpha()).isEqualTo(0.025);
        assertThat(analysis.comparisons().getFirst().verdict()).isEqualTo(Verdict.INCONCLUSIVE);
        assertThat(analysis.warnings()).anyMatch(w -> w.contains("Bonferroni"));
    }

    @Test
    void sampleRatioMismatchIsDetected() {
        var fine = ExperimentAnalyzer.sampleRatio(List.of(conversions(0, 5000, 0), conversions(1, 5200, 0)), Map.of(0, 1, 1, 1));
        assertThat(fine.pValue()).isCloseTo(0.0476703806561615, within(1e-10));
        assertThat(fine.mismatch()).isFalse();

        var broken = ExperimentAnalyzer.sampleRatio(List.of(conversions(0, 5000, 0), conversions(1, 5500, 0)), Map.of(0, 1, 1, 1));
        assertThat(broken.pValue()).isCloseTo(1.0635492679527356e-06, within(1e-14));
        assertThat(broken.mismatch()).isTrue();

        var threeWay = ExperimentAnalyzer.sampleRatio(
                List.of(conversions(0, 3400, 0), conversions(1, 3350, 0), conversions(2, 3250, 0)),
                Map.of(0, 34_000, 1, 33_000, 2, 33_000));
        assertThat(threeWay.pValue()).isCloseTo(0.46880153914023537, within(1e-10));
    }

    @Test
    void trafficInAZeroWeightVariationIsAMismatch() {
        var srm = ExperimentAnalyzer.sampleRatio(List.of(conversions(0, 1000, 0), conversions(1, 3, 0)), Map.of(0, 100_000, 1, 0));
        assertThat(srm.mismatch()).isTrue();
    }

    @Test
    void srmIsSkippedWithoutARollout() {
        var analysis = ExperimentAnalyzer.analyze(List.of(conversions(0, 1000, 100), conversions(1, 1000, 130)),
                0, MetricType.CONVERSION, true, 0.05, Map.of());
        assertThat(analysis.sampleRatio().checked()).isFalse();
        assertThat(analysis.warnings()).anyMatch(w -> w.contains("can't be checked"));
    }
}
