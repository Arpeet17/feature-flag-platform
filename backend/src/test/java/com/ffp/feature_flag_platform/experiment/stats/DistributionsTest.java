package com.ffp.feature_flag_platform.experiment.stats;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Reference values computed with SciPy 1.x (scipy.special.erfc, scipy.stats.norm/chi2/t). */
class DistributionsTest {

    @ParameterizedTest
    @CsvSource({
            "0.0, 1.0",
            "0.5, 0.4795001221869535",
            "1.0, 0.1572992070502851",
            "1.9, 0.007209570764742524",
            "2.0, 0.004677734981047266",
            "3.0, 2.209049699858544e-05",
            "5.0, 1.537459794428035e-12",
            "-1.0, 1.842700792949715",
    })
    void erfc(double x, double expected) {
        assertThat(Distributions.erfc(x)).isCloseTo(expected, within(Math.abs(expected) * 1e-12));
    }

    @ParameterizedTest
    @CsvSource({
            "0.0, 0.5",
            "1.0, 0.8413447460685429",
            "1.959963984540054, 0.975",
            "-2.575829303548901, 0.005",
            "4.0, 0.9999683287581669",
    })
    void normalCdf(double z, double expected) {
        assertThat(Distributions.normalCdf(z)).isCloseTo(expected, within(1e-12));
    }

    @ParameterizedTest
    @CsvSource({"0.975, 1.959963984540054", "0.5, 0.0", "0.8, 0.8416212335729143", "0.001, -3.090232306167814", "0.9999, 3.719016485455709"})
    void normalQuantile(double p, double expected) {
        assertThat(Distributions.normalQuantile(p)).isCloseTo(expected, within(1e-9));
    }

    @ParameterizedTest
    @CsvSource({
            "3.841458820694124, 1, 0.05",
            "5.991464547107979, 2, 0.05",
            "10.82756617046280, 1, 0.001",
            "1.0, 3, 0.8012519569012008",
            "50.0, 10, 2.669083424904495e-07",
            "0.0, 4, 1.0",
    })
    void chiSquareSurvival(double x, int k, double expected) {
        assertThat(Distributions.chiSquareSurvival(x, k)).isCloseTo(expected, within(Math.max(expected * 1e-9, 1e-15)));
    }

    @ParameterizedTest
    @CsvSource({
            "2.0, 10, 0.07338803477074037",
            "2.228138851986274, 10, 0.05",
            "1.0, 3.5, 0.3813372535634453",
            "0.0, 5, 1.0",
            "-2.0, 10, 0.07338803477074",
            "3.0, 1000, 0.0027667090442381925",
    })
    void studentT(double t, double df, double expected) {
        assertThat(Distributions.studentTTwoSidedP(t, df)).isCloseTo(expected, within(Math.max(expected * 1e-9, 1e-15)));
    }
}
