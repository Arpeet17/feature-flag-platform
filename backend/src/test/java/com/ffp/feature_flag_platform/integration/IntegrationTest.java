package com.ffp.feature_flag_platform.integration;

import com.ffp.feature_flag_platform.auth.Role;
import com.ffp.feature_flag_platform.common.Json;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Base for end-to-end tests through the full HTTP, security and persistence stack.
 * Each test works in its own uniquely named project so tests are independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTest {

    private static final Map<Role, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired
    protected MockMvc mvc;

    protected static String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected String token(Role role) {
        return TOKENS.computeIfAbsent(role, r -> {
            String adminToken = login("admin@ffp.local", "admin123");
            if (r == Role.ADMIN) {
                return adminToken;
            }
            String email = r.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6) + "@test.local";
            try {
                mvc.perform(post("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Json.write(Map.of("email", email, "displayName", r.name(), "password", "password123", "role", r))));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return login(email, "password123");
        });
    }

    protected String login(String email, String password) {
        try {
            String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(Json.write(Map.of("email", email, "password", password))))
                    .andReturn().getResponse().getContentAsString();
            return Json.MAPPER.readTree(body).get("accessToken").asString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected ResultActions as(Role role, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(role)));
    }

    protected ResultActions asAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return as(Role.ADMIN, request);
    }

    protected static MockHttpServletRequestBuilder getJson(String url) {
        return get(url);
    }

    protected static MockHttpServletRequestBuilder postJson(String url, Object body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : Json.write(body));
    }

    protected static MockHttpServletRequestBuilder putJson(String url, Object body) {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : Json.write(body));
    }

    protected static MockHttpServletRequestBuilder patchJson(String url, Object body) {
        return patch(url).contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
    }

    protected static MockHttpServletRequestBuilder deleteReq(String url) {
        return delete(url);
    }

    protected static JsonNode json(ResultActions result) throws Exception {
        return Json.MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Creates a project and returns environment key → raw SDK key. */
    protected Map<String, String> createProject(String projectKey) throws Exception {
        JsonNode created = json(asAdmin(postJson("/api/v1/projects", Map.of("key", projectKey, "name", "Test " + projectKey))));
        Map<String, String> keys = new java.util.HashMap<>();
        created.get("environments").forEach(e -> keys.put(e.get("environment").get("key").asString(), e.get("sdkKey").asString()));
        return keys;
    }

    protected ResultActions sdkEvaluate(String sdkKey, String flagKey, Map<String, Object> context) throws Exception {
        return mvc.perform(postJson("/api/v1/sdk/evaluate/" + flagKey, context).header("X-SDK-Key", sdkKey));
    }
}
