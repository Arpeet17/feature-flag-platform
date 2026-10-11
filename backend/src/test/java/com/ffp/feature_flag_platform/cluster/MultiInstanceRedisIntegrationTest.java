package com.ffp.feature_flag_platform.cluster;

import com.ffp.feature_flag_platform.FeatureFlagPlatformApplication;
import com.ffp.feature_flag_platform.common.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs two full instances of the application against one shared database and one Redis, and
 * checks that a change made through instance A becomes visible through instance B – including
 * B's evaluation cache, B's SSE clients and B's SDK key cache.
 *
 * <p>The snapshot max-age is set to an hour so only Redis pub/sub can explain B picking up the
 * change. Requires Redis on localhost:6379 (or FFP_TEST_REDIS_HOST/PORT); skipped locally when
 * unavailable, mandatory in CI where FFP_REQUIRE_REDIS=true.
 */
class MultiInstanceRedisIntegrationTest {

    private static final String REDIS_HOST = System.getenv().getOrDefault("FFP_TEST_REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(System.getenv().getOrDefault("FFP_TEST_REDIS_PORT", "6379"));
    private static final HttpClient http = HttpClient.newHttpClient();

    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;
    private static String a;
    private static String b;

    @BeforeAll
    static void startTwoInstances() {
        boolean redisUp = redisReachable();
        if (!redisUp && Boolean.parseBoolean(System.getenv("FFP_REQUIRE_REDIS"))) {
            fail("Redis is required (FFP_REQUIRE_REDIS=true) but not reachable at " + REDIS_HOST + ":" + REDIS_PORT);
        }
        assumeTrue(redisUp, "Redis not reachable; skipping multi-instance test");

        // Both instances share one in-memory database (same JVM, same named H2 DB) unless a real one is configured.
        String dbUrl = System.getenv().getOrDefault("FFP_DB_URL", "jdbc:h2:mem:cluster-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        String channel = "ffp:test:" + UUID.randomUUID();
        instanceA = start(dbUrl, channel);
        instanceB = start(dbUrl, channel);
        a = baseUrl(instanceA);
        b = baseUrl(instanceB);
    }

    @AfterAll
    static void stop() {
        if (instanceB != null) instanceB.close();
        if (instanceA != null) instanceA.close();
    }

    private static ConfigurableApplicationContext start(String dbUrl, String channel) {
        // Command-line args, not builder.properties(): those are defaults and application.yml would win.
        return new SpringApplicationBuilder(FeatureFlagPlatformApplication.class)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + dbUrl,
                        "--spring.data.redis.host=" + REDIS_HOST,
                        "--spring.data.redis.port=" + REDIS_PORT,
                        "--ffp.cluster.mode=redis",
                        "--ffp.cluster.channel=" + channel,
                        "--ffp.cluster.max-snapshot-age=PT1H");
    }

    @Test
    void changesMadeOnOneInstanceReachTheOther() throws Exception {
        String token = login(a);
        String project = "cluster-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = post(a, "/api/v1/projects", token, Map.of("key", project, "name", "Cluster test"));
        String sdkKey = null;
        for (JsonNode env : created.get("environments")) {
            if (env.at("/environment/key").asString().equals("production")) sdkKey = env.get("sdkKey").asString();
        }
        String key = sdkKey;
        post(a, "/api/v1/projects/" + project + "/flags", token, Map.of("key", "shared", "name", "Shared", "type", "BOOLEAN"));

        // Warm B's snapshot cache: B now holds "off" in memory.
        assertThat(evaluate(b, sdkKey).get("value").asBoolean()).isFalse();

        // Listen to B's SSE stream, then change the flag through A.
        var events = new CopyOnWriteArrayList<String>();
        Thread listener = Thread.ofVirtual().start(() -> readStream(b, key, events));
        awaitTrue(() -> events.contains("event:ready"), "B's stream to open");

        post(a, "/api/v1/projects/" + project + "/flags/shared/environments/production/toggle", token, Map.of("enabled", true));

        awaitTrue(() -> evaluate(b, key).get("value").asBoolean(), "B to serve the new value");
        awaitTrue(() -> events.contains("event:flags-changed"), "B to notify its SSE clients");
        listener.interrupt();

        // Rotating the key on A revokes the old key on B immediately (not after the 60s resolver cache).
        post(a, "/api/v1/projects/" + project + "/environments/production/sdk-key/rotate", token, Map.of());
        awaitTrue(() -> sdkStatus(b, key) == 401, "B to reject the rotated SDK key");
    }

    // ------------------------------------------------------------------ helpers

    private static boolean redisReachable() {
        try (Socket socket = new Socket(REDIS_HOST, REDIS_PORT)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String baseUrl(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getBean(Environment.class).getProperty("local.server.port");
    }

    private static String login(String base) throws Exception {
        return post(base, "/api/v1/auth/login", null, Map.of("email", "admin@ffp.local", "password", "admin123"))
                .get("accessToken").asString();
    }

    private static JsonNode post(String base, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s: %s", path, response.body()).isBetween(200, 299);
        return Json.MAPPER.readTree(response.body());
    }

    private static HttpResponse<String> sdkRequest(String base, String sdkKey) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/sdk/evaluate/shared"))
                .header("Content-Type", "application/json").header("X-SDK-Key", sdkKey)
                .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"user-1\"}")).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode evaluate(String base, String sdkKey) {
        try {
            HttpResponse<String> response = sdkRequest(base, sdkKey);
            assertThat(response.statusCode()).isEqualTo(200);
            return Json.MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int sdkStatus(String base, String sdkKey) {
        try {
            return sdkRequest(base, sdkKey).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void readStream(String base, String sdkKey, CopyOnWriteArrayList<String> lines) {
        var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/sdk/stream")).header("X-SDK-Key", sdkKey).build();
        try {
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                    lines.add(line.replace(": ", ":"));
                }
            }
        } catch (Exception ignored) {
            // stream closed at the end of the test
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + what);
    }
}
