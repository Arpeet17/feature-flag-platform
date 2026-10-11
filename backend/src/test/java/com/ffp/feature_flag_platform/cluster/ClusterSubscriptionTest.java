package com.ffp.feature_flag_platform.cluster;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ClusterSubscriptionTest {

    @Test
    void keepsRetryingUntilRedisIsReachable() {
        AtomicInteger attempts = new AtomicInteger();
        var subscription = new ClusterSubscription(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("Unable to connect to Redis");
            }
        }, () -> { }, Duration.ofMillis(10));

        subscription.start();

        await().atMost(Duration.ofSeconds(5)).until(subscription::isSubscribed);
        assertThat(attempts).hasValue(3);
        subscription.stop();
    }

    @Test
    void stopsRetryingOnShutdown() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger unsubscribes = new AtomicInteger();
        var subscription = new ClusterSubscription(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("down");
        }, unsubscribes::incrementAndGet, Duration.ofMillis(10));

        subscription.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> attempts.get() >= 1);
        subscription.stop();
        int afterStop = attempts.get();
        Thread.sleep(100);

        assertThat(attempts.get()).isLessThanOrEqualTo(afterStop + 1);
        assertThat(subscription.isSubscribed()).isFalse();
        assertThat(unsubscribes).as("never subscribed, nothing to unsubscribe").hasValue(0);
    }
}
