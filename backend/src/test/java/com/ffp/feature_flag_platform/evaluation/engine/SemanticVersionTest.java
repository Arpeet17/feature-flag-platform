package com.ffp.feature_flag_platform.evaluation.engine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticVersionTest {

    @ParameterizedTest
    @CsvSource({
            "1.0.0, 2.0.0",
            "2.0.0, 2.1.0",
            "2.1.0, 2.1.1",
            "1.0.0-alpha, 1.0.0",
            "1.0.0-alpha, 1.0.0-alpha.1",
            "1.0.0-alpha.1, 1.0.0-alpha.beta",
            "1.0.0-beta.2, 1.0.0-beta.11",
            "1.0.0-rc.1, 1.0.0",
            "2, 2.0.1",
    })
    void ordersAccordingToSpec(String lower, String higher) {
        var a = SemanticVersion.parse(lower).orElseThrow();
        var b = SemanticVersion.parse(higher).orElseThrow();
        assertThat(a.compareTo(b)).isNegative();
        assertThat(b.compareTo(a)).isPositive();
    }

    @ParameterizedTest
    @CsvSource({"1.2.3, v1.2.3", "1.2.3+build.5, 1.2.3", "2, 2.0.0", "2.1, 2.1.0"})
    void treatsEquivalentVersionsAsEqual(String a, String b) {
        assertThat(SemanticVersion.parse(a).orElseThrow().compareTo(SemanticVersion.parse(b).orElseThrow())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1.2.3.4", "01.2.3", "1..2"})
    void rejectsInvalidVersions(String input) {
        assertThat(SemanticVersion.parse(input)).isEmpty();
    }
}
