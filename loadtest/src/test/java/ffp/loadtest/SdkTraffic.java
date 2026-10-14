package ffp.loadtest;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * SDK traffic shared by the simulations. Uses an open workload model (new requests arrive at a
 * fixed rate regardless of how fast earlier ones complete), which is how real SDK traffic
 * behaves and avoids the coordinated-omission trap of closed-loop "N users in a loop" tests.
 */
final class SdkTraffic {

    private static final String[] COUNTRIES = {"US", "IN", "DE", "GB", "BR", "JP"};
    private static final String[] PLANS = {"free", "pro", "enterprise"};
    private static final String[] APP_VERSIONS = {"2.1.0", "2.2.9", "2.3.0", "2.4.1"};

    /** 100k distinct users, so rollout bucketing and rule matching do real work on every request. */
    static Iterator<Map<String, Object>> users() {
        return Stream.generate(() -> {
            var r = ThreadLocalRandom.current();
            int id = r.nextInt(100_000);
            return Map.<String, Object>of(
                    "userKey", "user-" + id,
                    "email", "user" + id + (r.nextInt(10) == 0 ? "@acme.com" : "@example.com"),
                    "country", COUNTRIES[r.nextInt(COUNTRIES.length)],
                    "plan", PLANS[r.nextInt(PLANS.length)],
                    "appVersion", APP_VERSIONS[r.nextInt(APP_VERSIONS.length)]);
        }).iterator();
    }

    private static final String CONTEXT = """
            {"key":"#{userKey}","attributes":{"email":"#{email}","country":"#{country}","plan":"#{plan}","appVersion":"#{appVersion}"}}""";

    static HttpProtocolBuilder protocol() {
        return http.baseUrl(Config.BASE_URL)
                .contentTypeHeader("application/json")
                .acceptHeader("application/json")
                .header("X-SDK-Key", Config.SDK_KEY)
                .shareConnections();
    }

    static final ChainBuilder EVALUATE_ALL = exec(http("sdk: evaluate all flags")
            .post("/api/v1/sdk/evaluate")
            .body(StringBody("{\"context\":" + CONTEXT + "}"))
            .check(status().is(200), jsonPath("$.flags.new-checkout.reason.kind").exists()));

    static final ChainBuilder EVALUATE_ONE = exec(http("sdk: evaluate one flag")
            .post("/api/v1/sdk/evaluate/new-checkout")
            .body(StringBody(CONTEXT))
            .check(status().is(200), jsonPath("$.value").exists()));

    static PopulationBuilder evaluateAll() {
        ScenarioBuilder s = scenario("SDK evaluate all").feed(users()).exec(EVALUATE_ALL);
        return s.injectOpen(arrivals(Config.RPS * 0.8));
    }

    static PopulationBuilder evaluateOne() {
        ScenarioBuilder s = scenario("SDK evaluate one").feed(users()).exec(EVALUATE_ONE);
        return s.injectOpen(arrivals(Config.RPS * 0.2));
    }

    private static io.gatling.javaapi.core.OpenInjectionStep[] arrivals(double rate) {
        return new io.gatling.javaapi.core.OpenInjectionStep[] {
                rampUsersPerSec(1).to(rate).during(Config.RAMP),
                constantUsersPerSec(rate).during(Config.DURATION)
        };
    }

    private SdkTraffic() {
    }
}
