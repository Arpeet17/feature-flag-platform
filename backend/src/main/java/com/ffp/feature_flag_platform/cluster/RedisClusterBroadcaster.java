package com.ffp.feature_flag_platform.cluster;

import com.ffp.feature_flag_platform.common.Json;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Fans changes out to every instance over a Redis pub/sub channel, and applies changes
 * received from other instances.
 *
 * <p>Pub/sub is at-most-once: a message published while an instance is disconnected is lost
 * for that instance. That is acceptable because the snapshot cache also has a maximum age
 * ({@code ffp.cluster.max-snapshot-age}), so a missed message delays a change by at most that
 * long instead of indefinitely. Publishing failures are logged and counted, never thrown –
 * the change is already committed and applied locally, so failing the request would be wrong.
 * Redis command timeouts are kept short (see application.yml) because publishing runs on the
 * request thread.
 */
public class RedisClusterBroadcaster implements ClusterBroadcaster, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisClusterBroadcaster.class);

    private final StringRedisTemplate redis;
    private final String channel;
    private final InstanceId instanceId;
    private final LocalChangeApplier applier;
    private final Counter published;
    private final Counter publishFailures;
    private final Counter received;
    private final Counter rejected;

    public RedisClusterBroadcaster(StringRedisTemplate redis, String channel, InstanceId instanceId,
                                   LocalChangeApplier applier, MeterRegistry meters) {
        this.redis = redis;
        this.channel = channel;
        this.instanceId = instanceId;
        this.applier = applier;
        this.published = counter(meters, "published");
        this.publishFailures = counter(meters, "publish_failed");
        this.received = counter(meters, "received");
        this.rejected = counter(meters, "rejected");
    }

    @Override
    public void broadcast(ClusterMessage message) {
        if (message.isEmpty()) {
            return;
        }
        try {
            redis.convertAndSend(channel, Json.write(message));
            published.increment();
        } catch (RuntimeException e) {
            publishFailures.increment();
            log.warn("Could not publish change to other instances; they will pick it up within the max snapshot age", e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        ClusterMessage change;
        try {
            change = Json.read(new String(message.getBody(), StandardCharsets.UTF_8), ClusterMessage.class);
        } catch (RuntimeException e) {
            rejected.increment();
            log.warn("Ignoring malformed cluster message on {}", channel, e);
            return;
        }
        if (instanceId.value().equals(change.origin())) {
            return; // already applied before publishing
        }
        received.increment();
        log.debug("Applying change from instance {}: environments {}", change.origin(), change.environmentIds());
        applier.apply(change.environmentIds(), change.revokedSdkKeyHashes());
    }

    private static Counter counter(MeterRegistry meters, String outcome) {
        return Counter.builder("ffp.cluster.messages").tag("outcome", outcome)
                .description("Cache invalidation messages exchanged with other instances").register(meters);
    }
}
