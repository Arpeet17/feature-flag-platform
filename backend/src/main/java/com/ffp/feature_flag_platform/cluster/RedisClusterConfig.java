package com.ffp.feature_flag_platform.cluster;

import com.ffp.feature_flag_platform.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Multi-instance mode ({@code ffp.cluster.mode=redis}).
 *
 * <p>The listener container is not started with the application context: it subscribes
 * eagerly and would fail startup whenever Redis is unavailable. {@link ClusterSubscription}
 * starts it in the background instead, so an instance can boot (and serve evaluations, which
 * never need Redis) while Redis is down.
 */
@Configuration
@ConditionalOnProperty(name = "ffp.cluster.mode", havingValue = "redis")
public class RedisClusterConfig {

    @Bean
    RedisClusterBroadcaster redisClusterBroadcaster(StringRedisTemplate redis, AppProperties properties, InstanceId instanceId,
                                                    LocalChangeApplier applier, MeterRegistry meters) {
        return new RedisClusterBroadcaster(redis, properties.cluster().channel(), instanceId, applier, meters);
    }

    @Bean
    RedisMessageListenerContainer clusterListenerContainer(RedisConnectionFactory connectionFactory,
                                                           RedisClusterBroadcaster broadcaster, AppProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(broadcaster, new ChannelTopic(properties.cluster().channel()));
        container.setAutoStartup(false);
        return container;
    }

    @Bean
    ClusterSubscription clusterSubscription(RedisMessageListenerContainer container) {
        return new ClusterSubscription(() -> {
            // After a failed start the container still believes it is running and a second start()
            // silently does nothing, so reset it first and verify the subscription really exists.
            container.stop();
            container.start();
            if (!container.isListening()) {
                container.stop();
                throw new IllegalStateException("subscription not established");
            }
        }, container::stop);
    }
}
