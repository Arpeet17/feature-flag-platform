package ffp.loadtest;

import java.time.Duration;

/** Load-test knobs, all overridable with -Dname=value. Defaults match the seeded demo project. */
final class Config {

    static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    static final String SDK_KEY = System.getProperty("sdkKey", "sdk-demo-production-00000000000000000000000");
    static final String ADMIN_EMAIL = System.getProperty("adminEmail", "admin@ffp.local");
    static final String ADMIN_PASSWORD = System.getProperty("adminPassword", "admin123");
    static final String PROJECT = System.getProperty("project", "demo-app");
    static final String ENVIRONMENT = System.getProperty("environment", "production");

    /** Total SDK requests per second (split 80/20 between evaluate-all and evaluate-one). */
    static final int RPS = Integer.getInteger("rps", 500);
    static final Duration RAMP = Duration.ofSeconds(Integer.getInteger("rampSeconds", 10));
    static final Duration DURATION = Duration.ofSeconds(Integer.getInteger("duration", 60));

    /** Pass/fail thresholds, in milliseconds. */
    static final int P95_MS = Integer.getInteger("p95Ms", 25);
    static final int P99_MS = Integer.getInteger("p99Ms", 50);

    private Config() {
    }
}
