package com.ffp.feature_flag_platform.integration;

import com.ffp.feature_flag_platform.auth.Role;
import com.ffp.feature_flag_platform.rollout.RolloutEngine;
import com.ffp.feature_flag_platform.rollout.RolloutEngine.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RolloutIntegrationTest extends IntegrationTest {

    @Autowired
    RolloutEngine engine;

    private record Setup(String base, String sdk, long rolloutId, Instant startedAt) {
    }

    /** Boolean flag; treatment = variation 0 ("On"), control = variation 1 ("Off"). */
    private Setup startRollout(List<Integer> steps, int intervalSeconds, String guardrail) throws Exception {
        String project = uniqueKey("ro");
        String sdk = createProject(project).get("production");
        String base = "/api/v1/projects/" + project;
        as(Role.EDITOR, postJson(base + "/flags", Map.of("key", "f", "name", "F", "type", "BOOLEAN")));
        Map<String, Object> body = new HashMap<>(Map.of("treatmentVariation", 0, "controlVariation", 1, "steps", steps,
                "stepIntervalSeconds", intervalSeconds));
        if (guardrail != null) body.put("guardrailEventKey", guardrail);
        Instant before = Instant.now();
        JsonNode r = json(as(Role.EDITOR, postJson(base + "/flags/f/environments/production/rollouts", body)).andExpect(status().isCreated()));
        return new Setup(base, sdk, r.get("id").asLong(), before);
    }

    private JsonNode rollout(Setup s) throws Exception {
        return json(asAdmin(get(s.base() + "/rollouts/" + s.rolloutId())));
    }

    private JsonNode fallthrough(Setup s) throws Exception {
        return json(asAdmin(get(s.base() + "/flags/f/environments/production"))).at("/targeting/fallthrough");
    }

    @Test
    void healthyRolloutRampsThroughEveryStepToCompletion() throws Exception {
        Setup s = startRollout(List.of(10, 50, 100), 60, null);
        assertThat(fallthrough(s).at("/rollout/variations/0/weight").asInt()).isEqualTo(10_000);
        assertThat(json(asAdmin(get(s.base() + "/flags/f/environments/production"))).get("enabled").asBoolean()).isTrue();

        Instant t0 = s.startedAt();
        assertThat(engine.process(s.rolloutId(), t0.plusSeconds(30))).isEqualTo(Outcome.HELD);
        assertThat(engine.process(s.rolloutId(), t0.plusSeconds(61))).isEqualTo(Outcome.ADVANCED);
        assertThat(fallthrough(s).at("/rollout/variations/0/weight").asInt()).isEqualTo(50_000);
        assertThat(engine.process(s.rolloutId(), t0.plusSeconds(123))).isEqualTo(Outcome.COMPLETED);

        assertThat(fallthrough(s).get("variation").asInt()).as("100% → fixed treatment").isZero();
        JsonNode r = rollout(s);
        assertThat(r.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(r.get("history").findValuesAsString("action")).containsExactly("STARTED", "ADVANCED", "COMPLETED");
        for (int i = 0; i < 20; i++) {
            sdkEvaluate(s.sdk(), "f", Map.of("key", "anyone-" + i)).andExpect(jsonPath("$.value").value(true));
        }
        asAdmin(get(s.base() + "/audit?resourceKey=f")).andExpect(jsonPath("$.items[0].actor").value("rollout-bot"));
    }

    @Test
    void regressionInRealSdkTrafficTriggersAutomaticRollback() throws Exception {
        Setup s = startRollout(List.of(50, 100), 3600, "error");

        // 2,000 users through the real SDK API; the new variation errors 15% of the time vs 3%.
        Random random = new Random(7);
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String user = "u" + i;
            JsonNode res = json(sdkEvaluate(s.sdk(), "f", Map.of("key", user)));
            int variation = res.get("variation").asInt();
            events.add(Map.of("kind", "exposure", "contextKey", user, "flagKey", "f", "variation", variation,
                    "inExperiment", res.at("/reason/inRollout").asBoolean(false)));
            if (random.nextDouble() < (variation == 0 ? 0.15 : 0.03)) {
                events.add(Map.of("kind", "custom", "contextKey", user, "eventKey", "error"));
            }
        }
        for (int i = 0; i < events.size(); i += 1000) {
            mvc.perform(postJson("/api/v1/sdk/events", Map.of("events", events.subList(i, Math.min(i + 1000, events.size()))))
                    .header("X-SDK-Key", s.sdk())).andExpect(status().isAccepted());
        }

        // Long before the step's hour is up, the guardrail check catches it.
        assertThat(engine.process(s.rolloutId(), Instant.now().plusSeconds(61))).isEqualTo(Outcome.ROLLED_BACK);

        JsonNode r = rollout(s);
        assertThat(r.get("status").asString()).isEqualTo("ROLLED_BACK");
        assertThat(r.get("lastDecision").asString()).contains("significantly worse");
        assertThat(fallthrough(s).get("variation").asInt()).as("everyone back on control").isEqualTo(1);
        for (int i = 0; i < 20; i++) {
            sdkEvaluate(s.sdk(), "f", Map.of("key", "u" + i)).andExpect(jsonPath("$.value").value(false));
        }
    }

    @Test
    void manualEditPausesTheRolloutInsteadOfBeingOverwritten() throws Exception {
        Setup s = startRollout(List.of(10, 100), 60, null);
        long version = json(asAdmin(get(s.base() + "/flags/f/environments/production"))).get("version").asLong();
        var manual = Map.of("offVariation", 1, "fallthrough", Map.of("variation", 1),
                "targets", List.of(Map.of("variation", 0, "values", List.of("vip"))));
        as(Role.EDITOR, putJson(s.base() + "/flags/f/environments/production", Map.of("version", version, "targeting", manual)))
                .andExpect(status().isOk());

        assertThat(engine.process(s.rolloutId(), s.startedAt().plusSeconds(61))).isEqualTo(Outcome.PAUSED);
        assertThat(rollout(s).get("lastDecision").asString()).contains("changed outside the rollout");
        assertThat(fallthrough(s).get("variation").asInt()).as("human change untouched").isEqualTo(1);

        // Resuming re-applies the current step on top of the human's targeting (their target survives).
        as(Role.EDITOR, post(s.base() + "/rollouts/" + s.rolloutId() + "/resume")).andExpect(jsonPath("$.status").value("RUNNING"));
        JsonNode config = json(asAdmin(get(s.base() + "/flags/f/environments/production")));
        assertThat(config.at("/targeting/fallthrough/rollout/variations/0/weight").asInt()).isEqualTo(10_000);
        assertThat(config.at("/targeting/targets/0/values/0").asString()).isEqualTo("vip");
    }

    @Test
    void concurrentProcessingAdvancesExactlyOnce() throws Exception {
        Setup s = startRollout(List.of(10, 50, 100), 60, null);
        Instant due = s.startedAt().plusSeconds(61);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Outcome>> calls = List.of(() -> engine.process(s.rolloutId(), due), () -> engine.process(s.rolloutId(), due),
                    () -> engine.process(s.rolloutId(), due), () -> engine.process(s.rolloutId(), due));
            List<Outcome> outcomes = new ArrayList<>();
            for (var f : pool.invokeAll(calls)) outcomes.add(f.get());
            assertThat(outcomes).containsOnlyOnce(Outcome.ADVANCED);
            assertThat(outcomes).filteredOn(o -> o != Outcome.ADVANCED).containsOnly(Outcome.SKIPPED);
        }
        assertThat(rollout(s).get("currentPercentage").asInt()).isEqualTo(50);
    }

    @Test
    void validationAndPermissions() throws Exception {
        Setup s = startRollout(List.of(10, 100), 60, null);
        String path = s.base() + "/flags/f/environments/production/rollouts";
        var valid = Map.of("treatmentVariation", 0, "controlVariation", 1, "steps", List.of(10, 100), "stepIntervalSeconds", 60);
        as(Role.EDITOR, postJson(path, valid)).andExpect(status().isConflict()); // one active rollout per flag/env
        as(Role.VIEWER, postJson(path, valid)).andExpect(status().isForbidden());

        as(Role.EDITOR, post(s.base() + "/rollouts/" + s.rolloutId() + "/cancel")).andExpect(jsonPath("$.status").value("CANCELLED"));
        as(Role.EDITOR, postJson(path, Map.of("treatmentVariation", 0, "controlVariation", 0, "steps", List.of(50, 20, 90),
                "stepIntervalSeconds", 1)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(4));
    }
}
