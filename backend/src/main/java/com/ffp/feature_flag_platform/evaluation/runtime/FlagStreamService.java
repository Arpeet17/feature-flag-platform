package com.ffp.feature_flag_platform.evaluation.runtime;

import com.ffp.feature_flag_platform.config.AppProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-Sent Events fan-out. SDKs hold one long-lived connection per environment and are
 * told when flags change, so updates propagate in milliseconds without polling. The event
 * carries only a version; clients re-fetch evaluations, which keeps the stream payload tiny
 * and avoids pushing per-user results.
 */
@Service
public class FlagStreamService {

    private static final Logger log = LoggerFactory.getLogger(FlagStreamService.class);

    public static final String EVENT_READY = "ready";
    public static final String EVENT_FLAGS_CHANGED = "flags-changed";

    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final AppProperties.Stream properties;

    public FlagStreamService(AppProperties properties, MeterRegistry meters) {
        this.properties = properties.stream();
        Gauge.builder("ffp.stream.connections", subscribers, s -> s.values().stream().mapToInt(Set::size).sum())
                .description("Open SDK streaming connections")
                .register(meters);
    }

    public SseEmitter subscribe(long environmentId, long currentVersion) {
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        Set<SseEmitter> set = subscribers.computeIfAbsent(environmentId, id -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        Runnable remove = () -> set.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(emitter, EVENT_READY, Map.of("version", currentVersion), set);
        return emitter;
    }

    public void publish(long environmentId, long version) {
        Set<SseEmitter> set = subscribers.getOrDefault(environmentId, Set.of());
        log.debug("Notifying {} subscriber(s) of environment {} (version {})", set.size(), environmentId, version);
        for (SseEmitter emitter : set) {
            send(emitter, EVENT_FLAGS_CHANGED, Map.of("version", version), set);
        }
    }

    public int connectionCount(long environmentId) {
        return subscribers.getOrDefault(environmentId, Set.of()).size();
    }

    /** Comment lines keep idle connections alive through load balancers and proxies. */
    @Scheduled(fixedRateString = "${ffp.stream.heartbeat}")
    void heartbeat() {
        subscribers.values().forEach(set -> set.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException e) {
                set.remove(emitter);
            }
        }));
    }

    private static void send(SseEmitter emitter, String name, Object data, Set<SseEmitter> set) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException e) {
            // Client went away; the container completes the emitter, we just stop tracking it.
            set.remove(emitter);
        }
    }
}
