package com.ffp.feature_flag_platform.evaluation.engine;

import com.ffp.feature_flag_platform.evaluation.model.Rollout;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BucketerTest {

    private static final int USERS = 100_000;

    @Test
    void isDeterministic() {
        assertThat(Bucketer.bucket("flag", "salt", "user-1")).isEqualTo(Bucketer.bucket("flag", "salt", "user-1"));
    }

    @Test
    void bucketsAreInRange() {
        IntStream.range(0, USERS).forEach(i ->
                assertThat(Bucketer.bucket("flag", "salt", "user-" + i)).isBetween(0, Rollout.TOTAL_WEIGHT - 1));
    }

    @Test
    void distributesUniformly() {
        long inFirstQuarter = IntStream.range(0, USERS)
                .filter(i -> Bucketer.bucket("flag", "salt", "user-" + i) < Rollout.TOTAL_WEIGHT / 4)
                .count();
        assertThat(inFirstQuarter / (double) USERS).isCloseTo(0.25, within(0.01));
    }

    @Test
    void rampUpOnlyAddsUsers() {
        // Everyone in a 10% rollout must still be in when it grows to 20%.
        IntStream.range(0, USERS).forEach(i -> {
            int bucket = Bucketer.bucket("flag", "salt", "user-" + i);
            if (bucket < 10_000) {
                assertThat(bucket).isLessThan(20_000);
            }
        });
    }

    @Test
    void saltDecorrelatesFlags() {
        // With independent flags, ~1% of users should be in both 10% rollouts (not 10%).
        long inBoth = IntStream.range(0, USERS)
                .filter(i -> Bucketer.bucket("flag-a", "salt-a", "user-" + i) < 10_000
                        && Bucketer.bucket("flag-b", "salt-b", "user-" + i) < 10_000)
                .count();
        assertThat(inBoth / (double) USERS).isCloseTo(0.01, within(0.003));
    }
}
