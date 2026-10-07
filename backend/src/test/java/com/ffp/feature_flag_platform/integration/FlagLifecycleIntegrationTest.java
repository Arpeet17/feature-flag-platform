package com.ffp.feature_flag_platform.integration;

import com.ffp.feature_flag_platform.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlagLifecycleIntegrationTest extends IntegrationTest {

    private String project;
    private Map<String, String> sdkKeys;
    private String flags;

    @BeforeEach
    void setUp() throws Exception {
        project = uniqueKey("proj");
        sdkKeys = createProject(project);
        flags = "/api/v1/projects/" + project + "/flags";
    }

    private void createBooleanFlag(String key) throws Exception {
        as(Role.EDITOR, postJson(flags, Map.of("key", key, "name", key, "type", "BOOLEAN", "tags", List.of("test"))))
                .andExpect(status().isCreated());
    }

    private long version(String flagKey, String env) throws Exception {
        return json(asAdmin(get(flags + "/" + flagKey + "/environments/" + env))).get("version").asLong();
    }

    @Test
    void newFlagIsOffEverywhereAndTogglingPropagatesToSdkImmediately() throws Exception {
        createBooleanFlag("new-ui");
        asAdmin(get(flags + "/new-ui"))
                .andExpect(jsonPath("$.environments.length()").value(3))
                .andExpect(jsonPath("$.environments[*].enabled", contains(false, false, false)));

        // Warm the snapshot cache, then make sure the toggle invalidates it.
        sdkEvaluate(sdkKeys.get("production"), "new-ui", Map.of("key", "u1"))
                .andExpect(jsonPath("$.value").value(false))
                .andExpect(jsonPath("$.reason.kind").value("OFF"));

        as(Role.EDITOR, postJson(flags + "/new-ui/environments/production/toggle", Map.of("enabled", true, "comment", "launch")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        sdkEvaluate(sdkKeys.get("production"), "new-ui", Map.of("key", "u1"))
                .andExpect(jsonPath("$.value").value(true))
                .andExpect(jsonPath("$.reason.kind").value("FALLTHROUGH"));
        // Other environments are unaffected.
        sdkEvaluate(sdkKeys.get("staging"), "new-ui", Map.of("key", "u1")).andExpect(jsonPath("$.value").value(false));

        asAdmin(get("/api/v1/projects/" + project + "/audit?resourceKey=new-ui"))
                .andExpect(jsonPath("$.items[0].action").value("TOGGLED"))
                .andExpect(jsonPath("$.items[0].summary").value(containsString("launch")))
                .andExpect(jsonPath("$.items[0].environmentKey").value("production"));
    }

    @Test
    void targetingRulesAreValidatedAppliedAndVersioned() throws Exception {
        createBooleanFlag("beta-feature");
        long v = version("beta-feature", "production");
        var targeting = Map.of(
                "offVariation", 1,
                "targets", List.of(Map.of("variation", 0, "values", List.of("vip"))),
                "rules", List.of(Map.of("description", "Acme staff",
                        "clauses", List.of(Map.of("attribute", "email", "operator", "ENDS_WITH", "values", List.of("@acme.com"))),
                        "serve", Map.of("variation", 0))),
                "fallthrough", Map.of("variation", 1));

        var updated = json(as(Role.EDITOR, putJson(flags + "/beta-feature/environments/production",
                Map.of("targeting", targeting, "enabled", true, "version", v))).andExpect(status().isOk()));
        assertThat(updated.get("version").asLong()).isEqualTo(v + 1);
        assertThat(updated.at("/targeting/rules/0/id").asString()).isNotBlank();

        String prod = sdkKeys.get("production");
        sdkEvaluate(prod, "beta-feature", Map.of("key", "vip")).andExpect(jsonPath("$.reason.kind").value("TARGET_MATCH"));
        sdkEvaluate(prod, "beta-feature", Map.of("key", "a", "attributes", Map.of("email", "a@acme.com")))
                .andExpect(jsonPath("$.value").value(true))
                .andExpect(jsonPath("$.reason.kind").value("RULE_MATCH"));
        sdkEvaluate(prod, "beta-feature", Map.of("key", "b", "attributes", Map.of("email", "b@gmail.com")))
                .andExpect(jsonPath("$.value").value(false));

        // Writing with the stale version is rejected instead of silently overwriting.
        as(Role.EDITOR, putJson(flags + "/beta-feature/environments/production", Map.of("targeting", targeting, "version", v)))
                .andExpect(status().isConflict());

        // Semantically invalid targeting is rejected with precise errors.
        as(Role.EDITOR, putJson(flags + "/beta-feature/environments/production", Map.of("version", v + 1,
                        "targeting", Map.of("offVariation", 4, "fallthrough", Map.of("rollout",
                                Map.of("variations", List.of(Map.of("variation", 0, "weight", 50))))))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors", hasItem(containsString("offVariation"))))
                .andExpect(jsonPath("$.errors", hasItem(containsString("must sum to 100000"))));
    }

    @Test
    void prerequisiteCyclesAreRejectedAndDependenciesProtectArchival() throws Exception {
        createBooleanFlag("parent");
        createBooleanFlag("child");
        var childNeedsParent = Map.of("offVariation", 1, "fallthrough", Map.of("variation", 0),
                "prerequisites", List.of(Map.of("flagKey", "parent", "variation", 0)));
        as(Role.EDITOR, putJson(flags + "/child/environments/staging",
                Map.of("targeting", childNeedsParent, "enabled", true, "version", version("child", "staging"))))
                .andExpect(status().isOk());

        var parentNeedsChild = Map.of("offVariation", 1, "fallthrough", Map.of("variation", 0),
                "prerequisites", List.of(Map.of("flagKey", "child", "variation", 0)));
        as(Role.EDITOR, putJson(flags + "/parent/environments/staging",
                Map.of("targeting", parentNeedsChild, "version", version("parent", "staging"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0]").value(containsString("cycle detected parent → child → parent")));

        // Parent is off in staging, so child fails its prerequisite.
        sdkEvaluate(sdkKeys.get("staging"), "child", Map.of("key", "u"))
                .andExpect(jsonPath("$.reason.kind").value("PREREQUISITE_FAILED"))
                .andExpect(jsonPath("$.reason.prerequisiteKey").value("parent"));

        as(Role.EDITOR, post(flags + "/parent/archive")).andExpect(status().isConflict());
        asAdmin(get(flags + "/parent")).andExpect(jsonPath("$.dependentFlags[0]").value("child"));
    }

    @Test
    void archiveRestoreAndDelete() throws Exception {
        createBooleanFlag("old-flag");
        as(Role.ADMIN, deleteReq(flags + "/old-flag")).andExpect(status().isConflict());

        as(Role.EDITOR, post(flags + "/old-flag/archive")).andExpect(jsonPath("$.archived").value(true));
        sdkEvaluate(sdkKeys.get("production"), "old-flag", Map.of("key", "u"))
                .andExpect(jsonPath("$.reason.errorKind").value("FLAG_NOT_FOUND"));
        asAdmin(get(flags)).andExpect(jsonPath("$.totalElements").value(0));
        asAdmin(get(flags + "?archived=true")).andExpect(jsonPath("$.items[0].key").value("old-flag"));

        as(Role.EDITOR, post(flags + "/old-flag/restore")).andExpect(jsonPath("$.archived").value(false));
        as(Role.EDITOR, post(flags + "/old-flag/archive")).andExpect(status().isOk());
        as(Role.EDITOR, deleteReq(flags + "/old-flag")).andExpect(status().isForbidden());
        as(Role.ADMIN, deleteReq(flags + "/old-flag")).andExpect(status().isNoContent());
        asAdmin(get(flags + "/old-flag")).andExpect(status().isNotFound());
    }

    @Test
    void multivariateFlagsValidateTypesAndProtectReferencedVariations() throws Exception {
        as(Role.EDITOR, postJson(flags, Map.of("key", "theme", "name", "Theme", "type", "STRING",
                "variations", List.of(Map.of("value", "light"), Map.of("value", 42)))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0]").value("variations[1]: value must be a string"));

        as(Role.EDITOR, postJson(flags, Map.of("key", "theme", "name", "Theme", "type", "STRING",
                "variations", List.of(Map.of("value", "light"), Map.of("value", "dark"), Map.of("value", "contrast")),
                "defaultOffVariation", 0))).andExpect(status().isCreated());

        as(Role.EDITOR, putJson(flags + "/theme/environments/development", Map.of("version", version("theme", "development"),
                "enabled", true, "targeting", Map.of("offVariation", 0, "fallthrough", Map.of("variation", 2)))))
                .andExpect(status().isOk());
        sdkEvaluate(sdkKeys.get("development"), "theme", Map.of("key", "u")).andExpect(jsonPath("$.value").value("contrast"));

        // Variation 2 is in use, so it cannot be removed.
        as(Role.EDITOR, patchJson(flags + "/theme", Map.of("variations", List.of(Map.of("value", "light"), Map.of("value", "dark")))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void searchFiltersByQueryAndTag() throws Exception {
        createBooleanFlag("search-me");
        as(Role.EDITOR, postJson(flags, Map.of("key", "other", "name", "Other", "type", "BOOLEAN", "tags", List.of("ops"))));
        asAdmin(get(flags + "?q=SEARCH")).andExpect(jsonPath("$.totalElements").value(1));
        asAdmin(get(flags + "?tag=ops")).andExpect(jsonPath("$.items[0].key").value("other"));
        asAdmin(get(flags + "/tags")).andExpect(jsonPath("$", contains("ops", "test")));
    }

    @Test
    void targetingCanBePromotedBetweenEnvironments() throws Exception {
        createBooleanFlag("promote");
        var targeting = Map.of("offVariation", 1, "fallthrough", Map.of("variation", 0),
                "targets", List.of(Map.of("variation", 1, "values", List.of("blocked"))));
        as(Role.EDITOR, putJson(flags + "/promote/environments/staging",
                Map.of("targeting", targeting, "enabled", true, "version", version("promote", "staging")))).andExpect(status().isOk());

        as(Role.EDITOR, postJson(flags + "/promote/environments/production/copy", Map.of(
                "sourceEnvironment", "staging", "includeEnabledState", true, "targetVersion", version("promote", "production"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.targeting.targets[0].values[0]").value("blocked"));
    }

    @Test
    void evaluationDebuggerExplainsResults() throws Exception {
        createBooleanFlag("debug-me");
        as(Role.VIEWER, postJson("/api/v1/projects/" + project + "/environments/production/evaluate",
                Map.of("context", Map.of("key", "u"), "flagKeys", List.of("debug-me", "nope"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flags.debug-me.reason.kind").value("OFF"))
                .andExpect(jsonPath("$.flags.nope.reason.errorKind").value("FLAG_NOT_FOUND"));
    }
}
