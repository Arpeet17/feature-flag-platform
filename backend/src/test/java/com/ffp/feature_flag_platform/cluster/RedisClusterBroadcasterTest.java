package com.ffp.feature_flag_platform.cluster;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RedisClusterBroadcasterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final LocalChangeApplier applier = mock(LocalChangeApplier.class);
    private final InstanceId self = new InstanceId();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RedisClusterBroadcaster broadcaster = new RedisClusterBroadcaster(redis, "ffp:test", self, applier, meters);

    private static DefaultMessage message(String json) {
        return new DefaultMessage("ffp:test".getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void appliesChangesFromOtherInstances() {
        broadcaster.onMessage(message("""
                {"origin":"other","environmentIds":[1,2],"revokedSdkKeyHashes":["abc"]}"""), null);
        verify(applier).apply(Set.of(1L, 2L), Set.of("abc"));
    }

    @Test
    void ignoresItsOwnMessages() {
        broadcaster.onMessage(message("{\"origin\":\"" + self.value() + "\",\"environmentIds\":[1]}"), null);
        verifyNoInteractions(applier);
    }

    @Test
    void ignoresMalformedMessages() {
        broadcaster.onMessage(message("not json"), null);
        verifyNoInteractions(applier);
        assertThat(meters.counter("ffp.cluster.messages", "outcome", "rejected").count()).isEqualTo(1);
    }

    @Test
    void publishFailureIsCountedNotThrown() {
        doThrow(new RuntimeException("redis down")).when(redis).convertAndSend(anyString(), anyString());
        broadcaster.broadcast(new ClusterMessage(self.value(), Set.of(1L), Set.of()));
        assertThat(meters.counter("ffp.cluster.messages", "outcome", "publish_failed").count()).isEqualTo(1);
    }

    @Test
    void emptyChangesAreNotPublished() {
        broadcaster.broadcast(new ClusterMessage(self.value(), Set.of(), Set.of()));
        verify(redis, never()).convertAndSend(eq("ffp:test"), anyString());
    }
}
