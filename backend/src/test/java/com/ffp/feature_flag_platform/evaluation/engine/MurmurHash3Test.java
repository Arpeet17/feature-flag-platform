package com.ffp.feature_flag_platform.evaluation.engine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MurmurHash3Test {

    @Test
    void matchesReferenceVectors() {
        // Reference values from the canonical C++ implementation (MurmurHash3_x86_32).
        assertThat(hash("", 0)).isEqualTo(0);
        assertThat(hash("", 1)).isEqualTo(0x514E28B7);
        assertThat(hash("hello", 0)).isEqualTo(0x248BFA47);
        assertThat(hash("The quick brown fox jumps over the lazy dog", 0)).isEqualTo(0x2E4FF723);
    }

    @Test
    void handlesAllTailLengths() {
        assertThat(hash("a", 0)).isEqualTo(0x3C2569B2);
        assertThat(hash("ab", 0)).isEqualTo(0x9BBFD75F);
        assertThat(hash("abc", 0)).isEqualTo(0xB3DD93FA);
        assertThat(hash("abcd", 0)).isEqualTo(0x43ED676A);
    }

    private static int hash(String s, int seed) {
        return MurmurHash3.hash32(s.getBytes(StandardCharsets.UTF_8), seed);
    }
}
