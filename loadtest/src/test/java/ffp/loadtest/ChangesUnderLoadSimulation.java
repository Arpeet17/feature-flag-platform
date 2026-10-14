package ffp.loadtest;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.during;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * SDK traffic while an admin flips a flag every two seconds. Each toggle commits, invalidates
 * the environment snapshot and forces a rebuild from the database, so this measures what a
 * change costs readers – the case a pure steady-state benchmark hides.
 *
 * <pre>mvn gatling:test -Dsimulation=ffp.loadtest.ChangesUnderLoadSimulation -Drps=500 -Dduration=60</pre>
 */
public class ChangesUnderLoadSimulation extends Simulation {

    private static final String TOGGLE_PATH = "/api/v1/projects/" + Config.PROJECT + "/flags/new-checkout/environments/"
            + Config.ENVIRONMENT + "/toggle";

    private final ScenarioBuilder admin = scenario("Admin toggling")
            .exec(http("admin: login")
                    .post("/api/v1/auth/login")
                    .header("X-SDK-Key", "")
                    .body(StringBody("{\"email\":\"" + Config.ADMIN_EMAIL + "\",\"password\":\"" + Config.ADMIN_PASSWORD + "\"}"))
                    .check(status().is(200), jsonPath("$.accessToken").saveAs("token")))
            .exec(session -> session.set("enabled", false))
            .pause(Config.RAMP)
            .exec(during(Config.DURATION).on(
                    exec(session -> session.set("enabled", !session.getBoolean("enabled")))
                            .exec(http("admin: toggle flag")
                                    .post(TOGGLE_PATH)
                                    .header("Authorization", "Bearer #{token}")
                                    .body(StringBody("{\"enabled\":#{enabled},\"comment\":\"load test\"}"))
                                    .check(status().is(200)))
                            .pause(Duration.ofSeconds(2))))
            // Leave the flag on, as the demo data has it.
            .exec(http("admin: restore flag")
                    .post(TOGGLE_PATH)
                    .header("Authorization", "Bearer #{token}")
                    .body(StringBody("{\"enabled\":true,\"comment\":\"load test cleanup\"}"))
                    .check(status().is(200)));

    {
        setUp(SdkTraffic.evaluateAll(), SdkTraffic.evaluateOne(), admin.injectOpen(atOnceUsers(1)))
                .protocols(SdkTraffic.protocol())
                .assertions(
                        global().failedRequests().percent().lt(0.1),
                        details("sdk: evaluate all flags").responseTime().percentile(99.0).lt(Config.P99_MS),
                        details("sdk: evaluate one flag").responseTime().percentile(99.0).lt(Config.P99_MS));
    }
}
