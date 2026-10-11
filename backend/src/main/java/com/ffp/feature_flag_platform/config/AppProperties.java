package com.ffp.feature_flag_platform.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Typed, validated application configuration (prefix {@code ffp}). Fails fast on startup if invalid. */
@Validated
@ConfigurationProperties(prefix = "ffp")
public record AppProperties(@Valid @NotNull Security security,
                            @Valid @NotNull Cors cors,
                            @Valid @NotNull Stream stream,
                            @Valid @NotNull Bootstrap bootstrap,
                            @Valid @NotNull Cluster cluster) {

    public record Security(@Valid @NotNull Jwt jwt) {
    }

    /**
     * @param secret HMAC-SHA256 signing secret; at least 32 bytes. Must be overridden outside dev.
     */
    public record Jwt(@NotBlank @Size(min = 32) String secret, @NotNull Duration ttl, @NotBlank String issuer) {
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }

    /**
     * @param heartbeat interval of SSE keep-alive comments (keeps proxies from closing idle streams)
     * @param timeout   max lifetime of a stream; clients reconnect, which rebalances load across instances
     */
    public record Stream(@NotNull Duration heartbeat, @NotNull Duration timeout) {
    }

    public enum ClusterMode { LOCAL, REDIS }

    /**
     * @param mode           LOCAL for a single instance; REDIS to propagate changes between instances via pub/sub
     * @param channel        Redis pub/sub channel shared by all instances of one deployment
     * @param maxSnapshotAge upper bound on how stale a cached snapshot may get if a change notification is lost
     *                       (pub/sub is at-most-once); zero disables the bound
     */
    public record Cluster(@NotNull ClusterMode mode, @NotBlank String channel, @NotNull Duration maxSnapshotAge) {
    }

    /**
     * @param adminEmail    when set and no users exist, an admin with this email is created on startup
     * @param demoData      seed a demo project with flags and segments
     * @param demoSdkKeys   fixed SDK keys for demo environments (env key → raw SDK key), dev only
     */
    public record Bootstrap(String adminEmail, String adminPassword, boolean demoData, Map<String, String> demoSdkKeys) {
        public Bootstrap {
            demoSdkKeys = demoSdkKeys == null ? Map.of() : Map.copyOf(demoSdkKeys);
        }
    }
}
