package com.ffp.feature_flag_platform.experiment.stats;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SequentialTestTest {

    @Test
    void likelihoodRatioMatchesReferenceImplementation() {
        // Reference: the closed form evaluated with NumPy.
        var bug = SequentialTest.conversion(200, 16, 1800, 36, 0.05);
        assertThat(bug.logLikelihoodRatio()).isCloseTo(7.710025004950502, within(1e-9));
        assertThat(bug.pValue()).isCloseTo(0.0004483102670179875, within(1e-12));
        assertThat(bug.rejected()).isTrue();

        // The healthy release a naive z-test rolled back (p = 0.049): no real evidence.
        var noise = SequentialTest.conversion(162, 7, 1588, 31, 0.05);
        assertThat(noise.logLikelihoodRatio()).isCloseTo(0.7034228721323748, within(1e-9));
        assertThat(noise.rejected()).isFalse();
    }

    @Test
    void conservativeComparedWithASingleFixedHorizonTest() {
        // Fixed-horizon p = 0.035 (see ExperimentAnalyzerTest); the always-valid p is larger:
        // that is what pays for being allowed to look at every check.
        var r = SequentialTest.conversion(1000, 130, 1000, 100, 0.05);
        assertThat(r.pValue()).isCloseTo(0.3881282492098256, within(1e-12));
        assertThat(r.rejected()).isFalse();
    }

    @Test
    void degenerateInputsCarryNoEvidence() {
        assertThat(SequentialTest.conversion(0, 0, 100, 3, 0.05).pValue()).isEqualTo(1);
        assertThat(SequentialTest.conversion(100, 0, 100, 0, 0.05).pValue()).isEqualTo(1);
        assertThat(SequentialTest.conversion(100, 100, 100, 100, 0.05).rejected()).isFalse();
    }

    /**
     * The reason this test exists. A/A rollouts (no real difference) checked 5 times per step over
     * 3 steps: re-running a z-test at every check rolls back about a fifth of healthy releases; the
     * sequential test keeps false rollbacks within α, while still catching a real regression.
     */
    @Test
    void peekingInflatesFalseAlarmsButTheSequentialTestDoesNot() {
        Random random = new Random(20261014);
        int trials = 600;
        int naive = 0;
        int sequential = 0;
        for (int i = 0; i < trials; i++) {
            if (simulateRollout(random, 0.02, 0.02, Method.NAIVE)) naive++;
            if (simulateRollout(random, 0.02, 0.02, Method.SEQUENTIAL)) sequential++;
        }
        assertThat(naive / (double) trials).as("naive z-test at every check").isGreaterThan(0.12);
        assertThat(sequential / (double) trials).as("mSPRT, any number of checks").isLessThan(0.05);

        int caught = 0;
        for (int i = 0; i < 200; i++) {
            if (simulateRollout(random, 0.02, 0.08, Method.SEQUENTIAL)) caught++;
        }
        assertThat(caught).as("a 2% → 8% error-rate regression").isEqualTo(200);
    }

    private enum Method { NAIVE, SEQUENTIAL }

    /** Steps 10/25/50%, 5 checks per step, 750 users between checks. True if it rolled back. */
    private static boolean simulateRollout(Random random, double controlRate, double treatmentRate, Method method) {
        List<Integer> steps = List.of(10, 25, 50);
        for (int pct : steps) {
            long nt = 0, xt = 0, nc = 0, xc = 0;
            for (int check = 0; check < 5; check++) {
                for (int u = 0; u < 750; u++) {
                    if (random.nextInt(100) < pct) {
                        nt++;
                        if (random.nextDouble() < treatmentRate) xt++;
                    } else {
                        nc++;
                        if (random.nextDouble() < controlRate) xc++;
                    }
                }
                if (Math.min(nt, nc) < 100 || Math.min(xt, xc) < 5 || xt * nc <= xc * nt) {
                    continue; // same data floor as the analyzer; only "worse" triggers a rollback
                }
                boolean rollBack = method == Method.NAIVE
                        ? naivePValue(nt, xt, nc, xc) < 0.05
                        : SequentialTest.conversion(nt, xt, nc, xc, 0.05 / steps.size()).rejected();
                if (rollBack) return true;
            }
        }
        return false;
    }

    private static double naivePValue(long nt, long xt, long nc, long xc) {
        var a = ExperimentAnalyzer.analyze(List.of(
                        new ExperimentAnalyzer.ArmData(0, "c", nc, xc, 0, 0), new ExperimentAnalyzer.ArmData(1, "t", nt, xt, 0, 0)),
                0, ExperimentAnalyzer.MetricType.CONVERSION, false, 0.05, Map.of());
        return a.comparisons().getFirst().pValue();
    }
}
