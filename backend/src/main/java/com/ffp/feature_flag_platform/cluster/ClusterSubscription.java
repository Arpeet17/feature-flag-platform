package com.ffp.feature_flag_platform.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;

/**
 * Establishes the Redis subscription on a background thread, retrying with exponential backoff
 * until it succeeds. Once subscribed, the listener container recovers from later disconnects
 * by itself. Messages published while this instance is not subscribed are lost (pub/sub is
 * at-most-once); the snapshot max-age bounds how long that can leave this instance stale.
 */
public class ClusterSubscription implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ClusterSubscription.class);
    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final Runnable subscribe;
    private final Runnable unsubscribe;
    private final Duration initialBackoff;
    private volatile Thread worker;
    private volatile boolean running;
    private volatile boolean subscribed;

    public ClusterSubscription(Runnable subscribe, Runnable unsubscribe) {
        this(subscribe, unsubscribe, INITIAL_BACKOFF);
    }

    ClusterSubscription(Runnable subscribe, Runnable unsubscribe, Duration initialBackoff) {
        this.subscribe = subscribe;
        this.unsubscribe = unsubscribe;
        this.initialBackoff = initialBackoff;
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("ffp-cluster-subscribe").start(this::subscribeWithRetry);
    }

    private void subscribeWithRetry() {
        Duration backoff = initialBackoff;
        for (int attempt = 1; running; attempt++) {
            try {
                subscribe.run();
                subscribed = true;
                log.info("Subscribed to cluster change notifications");
                return;
            } catch (RuntimeException e) {
                log.warn("Cluster subscription attempt {} failed ({}); retrying in {}ms. Changes from other "
                        + "instances arrive via snapshot expiry until then.", attempt, e.getMessage(), backoff.toMillis());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    public boolean isSubscribed() {
        return subscribed;
    }

    @Override
    public void stop() {
        running = false;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        if (subscribed) {
            unsubscribe.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
