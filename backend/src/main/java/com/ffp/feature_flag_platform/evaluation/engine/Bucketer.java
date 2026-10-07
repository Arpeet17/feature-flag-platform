package com.ffp.feature_flag_platform.evaluation.engine;

import com.ffp.feature_flag_platform.evaluation.model.Rollout;

import java.nio.charset.StandardCharsets;

/**
 * Deterministically maps a (flag, context) pair to a bucket in {@code [0, Rollout.TOTAL_WEIGHT)}.
 *
 * <p>Properties this guarantees:
 * <ul>
 *   <li><b>Sticky</b> – the same user always gets the same bucket, on every server.</li>
 *   <li><b>Monotonic ramp-ups</b> – rollouts allocate buckets cumulatively, so raising a
 *       rollout from 10% to 20% only adds users; nobody who had the feature loses it.</li>
 *   <li><b>Independent across flags</b> – the per-flag salt decorrelates buckets, so the
 *       same 10% of users are not the guinea pigs for every experiment.</li>
 * </ul>
 */
public final class Bucketer {

    private Bucketer() {
    }

    public static int bucket(String flagKey, String salt, String bucketValue) {
        String input = flagKey + "." + salt + "." + bucketValue;
        int hash = MurmurHash3.hash32(input.getBytes(StandardCharsets.UTF_8), 0);
        return (int) (Integer.toUnsignedLong(hash) % Rollout.TOTAL_WEIGHT);
    }
}
