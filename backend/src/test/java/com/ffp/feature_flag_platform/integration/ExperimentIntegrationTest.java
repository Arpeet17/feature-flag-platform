package com.ffp.feature_flag_platform.integration;

import com.ffp.feature_flag_platform.auth.Role;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End to end: SDK assigns users, events flow in, the experiment detects a planted effect. */
class ExperimentIntegrationTest extends IntegrationTest {

    @Test
    void detectsAPlantedEffectFromRealSdkTraffic() throws Exception {
        String project = uniqueKey("exp");
        String sdk = createProject(project).get("production");
        String base = "/api/v1/projects/" + project;

        as(Role.EDITOR, postJson(base + "/flags", Map.of("key", "checkout", "name", "Checkout", "type", "BOOLEAN")));
        long v = json(asAdmin(get(base + "/flags/checkout/environments/production"))).get("version").asLong();
        // Staff are targeted by a rule (NOT randomized); everyone else gets a 50/50 rollout.
        var staffRule = Map.of(
                "clauses", List.of(Map.of("attribute", "email", "operator", "ENDS_WITH", "values", List.of("@staff.io"))),
                "serve", Map.of("variation", 0));
        var fiftyFifty = Map.of("rollout", Map.of("variations", List.of(
                Map.of("variation", 0, "weight", 50_000), Map.of("variation", 1, "weight", 50_000))));
        var targeting = Map.of("offVariation", 1, "rules", List.of(staffRule), "fallthrough", fiftyFifty);
        as(Role.EDITOR, putJson(base + "/flags/checkout/environments/production",
                Map.of("version", v, "enabled", true, "targeting", targeting)))
                .andExpect(status().isOk());

        as(Role.EDITOR, postJson(base + "/experiments", Map.of("key", "checkout-test", "name", "New checkout",
                "environment", "production", "flagKey", "checkout", "metricEventKey", "purchase",
                "metricType", "CONVERSION", "controlVariation", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        // 3,000 users: the SDK decides their variation; variation 0 (new checkout) truly converts
        // at 20%, control at 10%.
        Random random = new Random(42);
        List<Map<String, Object>> events = new ArrayList<>();
        Map<Integer, Integer> assigned = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            String user = "user-" + i;
            JsonNode result = json(sdkEvaluate(sdk, "checkout", Map.of("key", user)));
            int variation = result.get("variation").asInt();
            assigned.merge(variation, 1, Integer::sum);
            events.add(Map.of("kind", "exposure", "contextKey", user, "flagKey", "checkout", "variation", variation,
                    "inExperiment", result.at("/reason/inRollout").asBoolean(false)));
            if (random.nextDouble() < (variation == 0 ? 0.20 : 0.10)) {
                events.add(Map.of("kind", "custom", "contextKey", user, "eventKey", "purchase", "value", 49.0));
            }
        }
        // Noise that must not affect results:
        events.add(Map.of("kind", "exposure", "contextKey", "user-0", "flagKey", "checkout", "variation", 1, "inExperiment", true)); // duplicate exposure
        for (int i = 0; i < 50; i++) {
            events.add(Map.of("kind", "exposure", "contextKey", "staff-" + i, "flagKey", "checkout", "variation", 0, "inExperiment", false)); // rule-targeted
            events.add(Map.of("kind", "custom", "contextKey", "staff-" + i, "eventKey", "purchase"));
        }
        events.add(Map.of("kind", "custom", "contextKey", "user-1", "eventKey", "purchase",
                "timestamp", System.currentTimeMillis() - 30L * 24 * 3600 * 1000)); // too old: rejected

        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < events.size(); i += 1000) {
            JsonNode r = json(mvc.perform(postJson("/api/v1/sdk/events", Map.of("events", events.subList(i, Math.min(events.size(), i + 1000))))
                    .header("X-SDK-Key", sdk)).andExpect(status().isAccepted()));
            accepted += r.get("accepted").asInt();
            rejected += r.get("rejected").asInt();
        }
        assertThat(rejected).isEqualTo(1);
        assertThat(accepted).isEqualTo(events.size() - 1);

        JsonNode exp = json(as(Role.VIEWER, get(base + "/experiments/checkout-test")).andExpect(status().isOk()));
        JsonNode analysis = exp.at("/results/analysis");
        long usersCounted = 0;
        for (JsonNode arm : analysis.get("arms")) {
            usersCounted += arm.get("users").asLong();
            assertThat(arm.get("users").asLong()).isEqualTo((long) assigned.get(arm.get("variation").asInt()));
        }
        assertThat(usersCounted).as("duplicates and rule-targeted users excluded").isEqualTo(3000);
        assertThat(exp.at("/results/excludedNonRandomized").asLong()).isEqualTo(50);

        JsonNode comparison = analysis.get("comparisons").get(0);
        assertThat(comparison.get("variation").asInt()).isZero();
        assertThat(comparison.get("verdict").asString()).isEqualTo("BETTER");
        assertThat(comparison.get("pValue").asDouble()).isLessThan(0.001);
        assertThat(comparison.get("absoluteLift").asDouble()).isBetween(0.06, 0.14);
        assertThat(analysis.at("/sampleRatio/mismatch").asBoolean()).isFalse();

        as(Role.EDITOR, post(base + "/experiments/checkout-test/stop"))
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.results.analysis.comparisons[0].verdict").value("BETTER"));
        as(Role.VIEWER, post(base + "/experiments/checkout-test/stop")).andExpect(status().isForbidden());
    }

    @Test
    void conversionsBeforeExposureDoNotCount() throws Exception {
        String project = uniqueKey("exp-order");
        String sdk = createProject(project).get("production");
        String base = "/api/v1/projects/" + project;
        as(Role.EDITOR, postJson(base + "/flags", Map.of("key", "f", "name", "F", "type", "BOOLEAN")));
        as(Role.EDITOR, postJson(base + "/experiments", Map.of("key", "e", "name", "E", "environment", "production",
                "flagKey", "f", "metricEventKey", "signup", "metricType", "CONVERSION")));

        Thread.sleep(1_500); // the experiment started ~1.5s ago; place events inside its window, in the past
        long now = System.currentTimeMillis();
        var batch = Map.of("events", List.of(
                Map.of("kind", "custom", "contextKey", "u1", "eventKey", "signup", "timestamp", now - 1_200),
                Map.of("kind", "exposure", "contextKey", "u1", "flagKey", "f", "variation", 0, "inExperiment", true, "timestamp", now - 800),
                Map.of("kind", "exposure", "contextKey", "u2", "flagKey", "f", "variation", 0, "inExperiment", true, "timestamp", now - 800),
                Map.of("kind", "custom", "contextKey", "u2", "eventKey", "signup", "timestamp", now - 400)));
        mvc.perform(postJson("/api/v1/sdk/events", batch).header("X-SDK-Key", sdk)).andExpect(status().isAccepted());

        JsonNode arm = json(asAdmin(get(base + "/experiments/e"))).at("/results/analysis/arms/0");
        assertThat(arm.get("users").asLong()).isEqualTo(2);
        assertThat(arm.get("conversions").asLong()).as("u1 converted before seeing the variation").isEqualTo(1);
    }

    @Test
    void invalidEventBatchesAreRejected() throws Exception {
        String sdk = createProject(uniqueKey("exp-bad")).get("production");
        var badBatch = Map.of("events", List.of(Map.of("kind", "teleport", "contextKey", "u")));
        mvc.perform(postJson("/api/v1/sdk/events", badBatch).header("X-SDK-Key", sdk)).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/v1/sdk/events", Map.of("events", List.of()))).andExpect(status().isUnauthorized());
    }
}
