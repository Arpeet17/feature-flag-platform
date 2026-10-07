package com.ffp.feature_flag_platform.integration;

import com.ffp.feature_flag_platform.auth.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EnvironmentAndSegmentIntegrationTest extends IntegrationTest {

    @Test
    void sdkKeyRotationRevokesOldKey() throws Exception {
        String project = uniqueKey("rot");
        String oldKey = createProject(project).get("development");
        mvc.perform(postJson("/api/v1/sdk/evaluate", Map.of("context", Map.of("key", "u"))).header("X-SDK-Key", oldKey))
                .andExpect(status().isOk());

        String newKey = json(asAdmin(post("/api/v1/projects/" + project + "/environments/development/sdk-key/rotate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sdkKey").value(startsWith("sdk-")))).get("sdkKey").asString();

        mvc.perform(postJson("/api/v1/sdk/evaluate", Map.of("context", Map.of("key", "u"))).header("X-SDK-Key", oldKey))
                .andExpect(status().isUnauthorized());
        mvc.perform(postJson("/api/v1/sdk/evaluate", Map.of("context", Map.of("key", "u"))).header("Authorization", newKey))
                .andExpect(status().isOk());
    }

    @Test
    void newEnvironmentGetsConfigForExistingFlags() throws Exception {
        String project = uniqueKey("env");
        createProject(project);
        as(Role.EDITOR, postJson("/api/v1/projects/" + project + "/flags", Map.of("key", "f", "name", "F", "type", "BOOLEAN")));

        String qaKey = json(asAdmin(postJson("/api/v1/projects/" + project + "/environments",
                Map.of("key", "qa", "name", "QA", "color", "#123456"))).andExpect(status().isCreated())).get("sdkKey").asString();

        asAdmin(get("/api/v1/projects/" + project + "/flags/f/environments/qa")).andExpect(jsonPath("$.enabled").value(false));
        sdkEvaluate(qaKey, "f", Map.of("key", "u")).andExpect(jsonPath("$.reason.kind").value("OFF"));
    }

    @Test
    void segmentsAreUsableInRulesAndProtectedWhileReferenced() throws Exception {
        String project = uniqueKey("seg");
        String sdk = createProject(project).get("production");
        String base = "/api/v1/projects/" + project;

        as(Role.EDITOR, postJson(base + "/segments", Map.of("key", "enterprise", "name", "Enterprise",
                "included", List.of("big-co-admin"),
                "rules", List.of(Map.of("clauses", List.of(Map.of("attribute", "plan", "operator", "IN", "values", List.of("enterprise"))))))))
                .andExpect(status().isCreated());
        as(Role.EDITOR, postJson(base + "/flags", Map.of("key", "sso", "name", "SSO", "type", "BOOLEAN")));
        long v = json(asAdmin(get(base + "/flags/sso/environments/production"))).get("version").asLong();
        as(Role.EDITOR, putJson(base + "/flags/sso/environments/production", Map.of("version", v, "enabled", true,
                "targeting", Map.of("offVariation", 1, "fallthrough", Map.of("variation", 1),
                        "rules", List.of(Map.of("clauses", List.of(Map.of("operator", "SEGMENT_MATCH", "values", List.of("enterprise"))),
                                "serve", Map.of("variation", 0)))))))
                .andExpect(status().isOk());

        sdkEvaluate(sdk, "sso", Map.of("key", "big-co-admin")).andExpect(jsonPath("$.value").value(true));
        sdkEvaluate(sdk, "sso", Map.of("key", "x", "attributes", Map.of("plan", "enterprise"))).andExpect(jsonPath("$.value").value(true));
        sdkEvaluate(sdk, "sso", Map.of("key", "y", "attributes", Map.of("plan", "free"))).andExpect(jsonPath("$.value").value(false));

        // Updating the segment changes evaluation without touching the flag.
        long segVersion = json(asAdmin(get(base + "/segments/enterprise"))).get("version").asLong();
        as(Role.EDITOR, putJson(base + "/segments/enterprise", Map.of("name", "Enterprise", "version", segVersion,
                "included", List.of("big-co-admin"), "excluded", List.of("x"),
                "rules", List.of(Map.of("clauses", List.of(Map.of("attribute", "plan", "operator", "IN", "values", List.of("enterprise"))))))))
                .andExpect(status().isOk());
        sdkEvaluate(sdk, "sso", Map.of("key", "x", "attributes", Map.of("plan", "enterprise"))).andExpect(jsonPath("$.value").value(false));

        asAdmin(get(base + "/segments")).andExpect(jsonPath("$[0].usedByFlags[0]").value("sso"));
        as(Role.EDITOR, deleteReq(base + "/segments/enterprise")).andExpect(status().isConflict());
    }

    @Test
    void cannotDeleteLastEnvironment() throws Exception {
        String project = uniqueKey("last");
        createProject(project);
        asAdmin(deleteReq("/api/v1/projects/" + project + "/environments/development")).andExpect(status().isNoContent());
        asAdmin(deleteReq("/api/v1/projects/" + project + "/environments/staging")).andExpect(status().isNoContent());
        asAdmin(deleteReq("/api/v1/projects/" + project + "/environments/production")).andExpect(status().isConflict());
    }

    @Test
    void duplicateKeysAndBadInputAreRejected() throws Exception {
        String project = uniqueKey("dup");
        createProject(project);
        asAdmin(postJson("/api/v1/projects", Map.of("key", project, "name", "again"))).andExpect(status().isConflict());
        asAdmin(postJson("/api/v1/projects", Map.of("key", "Bad Key!", "name", "x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(startsWith("key must be")));
    }
}
