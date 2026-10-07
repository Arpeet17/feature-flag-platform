package com.ffp.feature_flag_platform.flag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TargetingSummaryTest {
    @Test
    void percentFormatting() {
        assertThat(TargetingSummary.percent(10_000)).isEqualTo("10");
        assertThat(TargetingSummary.percent(33_333)).isEqualTo("33.333");
        assertThat(TargetingSummary.percent(500)).isEqualTo("0.5");
        assertThat(TargetingSummary.percent(0)).isEqualTo("0");
    }
}
