package com.ffp.feature_flag_platform.environment;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves SDK keys to environments on every SDK request, so lookups are cached. Entries
 * expire after a short TTL so that a rotated key stops working on <em>every</em> instance
 * within that window, not just the one that handled the rotation. Unknown keys are not
 * cached, so random keys cannot be used to grow the cache.
 */
@Component
public class SdkKeyResolver {

    static final Duration TTL = Duration.ofSeconds(60);

    private final EnvironmentRepository environments;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public SdkKeyResolver(EnvironmentRepository environments, Clock clock) {
        this.environments = environments;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<SdkEnvironment> resolve(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(SdkKeys.PREFIX)) {
            return Optional.empty();
        }
        String hash = SdkKeys.hash(rawKey);
        Instant now = clock.instant();
        Entry cached = cache.get(hash);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return Optional.of(cached.environment());
        }
        Optional<SdkEnvironment> resolved = environments.findBySdkKeyHash(hash)
                .map(e -> new SdkEnvironment(e.getId(), e.getKey(), e.getProject().getId(), e.getProject().getKey()));
        resolved.ifPresentOrElse(
                env -> cache.put(hash, new Entry(env, now.plus(TTL))),
                () -> cache.remove(hash));
        return resolved;
    }

    public void evict(String keyHash) {
        cache.remove(keyHash);
    }

    public record SdkEnvironment(long environmentId, String environmentKey, long projectId, String projectKey) {
    }

    private record Entry(SdkEnvironment environment, Instant expiresAt) {
    }
}
