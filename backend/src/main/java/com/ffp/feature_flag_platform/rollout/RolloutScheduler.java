package com.ffp.feature_flag_platform.rollout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Drives due rollouts. Runs on every instance; row claiming in {@link RolloutEngine} makes sure
 * each rollout is processed by one instance at a time. A failure in one rollout never blocks
 * the others.
 */
@Component
@ConditionalOnProperty(name = "ffp.rollouts.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class RolloutScheduler {

    private static final Logger log = LoggerFactory.getLogger(RolloutScheduler.class);

    private final RolloutRepository rollouts;
    private final RolloutEngine engine;
    private final Clock clock;

    public RolloutScheduler(RolloutRepository rollouts, RolloutEngine engine, Clock clock) {
        this.rollouts = rollouts;
        this.engine = engine;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ffp.rollouts.tick:PT2S}")
    public void tick() {
        Instant now = clock.instant();
        for (Long id : rollouts.findDueIds(now)) {
            try {
                engine.process(id, now);
            } catch (RuntimeException e) {
                log.error("Rollout {} check failed; will retry on the next tick", id, e);
            }
        }
    }
}
