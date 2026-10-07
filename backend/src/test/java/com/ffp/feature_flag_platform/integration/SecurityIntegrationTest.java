package com.ffp.feature_flag_platform.integration;

import com.ffp.feature_flag_platform.auth.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIntegrationTest extends IntegrationTest {

    @Test
    void loginIssuesTokenForValidCredentials() throws Exception {
        mvc.perform(postJson("/api/v1/auth/login", Map.of("email", "admin@ffp.local", "password", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void loginRejectsBadPassword() throws Exception {
        mvc.perform(postJson("/api/v1/auth/login", Map.of("email", "admin@ffp.local", "password", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void apiRequiresToken() throws Exception {
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        as(Role.EDITOR, get("/api/v1/auth/me")).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("EDITOR"));
    }

    @Test
    void roleHierarchyIsEnforced() throws Exception {
        String project = uniqueKey("rbac");
        createProject(project);
        var flag = Map.of("key", "f", "name", "F", "type", "BOOLEAN");

        as(Role.VIEWER, get("/api/v1/projects/" + project + "/flags")).andExpect(status().isOk());
        as(Role.VIEWER, postJson("/api/v1/projects/" + project + "/flags", flag)).andExpect(status().isForbidden());
        as(Role.EDITOR, postJson("/api/v1/projects/" + project + "/flags", flag)).andExpect(status().isCreated());
        as(Role.EDITOR, postJson("/api/v1/projects", Map.of("key", uniqueKey("p"), "name", "P"))).andExpect(status().isForbidden());
        as(Role.EDITOR, get("/api/v1/users")).andExpect(status().isForbidden());
        asAdmin(get("/api/v1/users")).andExpect(status().isOk());
    }

    @Test
    void sdkEndpointsRequireValidSdkKey() throws Exception {
        mvc.perform(postJson("/api/v1/sdk/evaluate", Map.of("context", Map.of("key", "u"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(postJson("/api/v1/sdk/evaluate", Map.of("context", Map.of("key", "u"))).header("X-SDK-Key", "sdk-nope"))
                .andExpect(status().isUnauthorized());
        // An admin JWT is not an SDK key.
        asAdmin(postJson("/api/v1/sdk/evaluate", Map.of("context", Map.of("key", "u")))).andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
