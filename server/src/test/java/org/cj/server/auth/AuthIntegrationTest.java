package org.cj.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import org.cj.server.auth.dto.LoginRequest;
import org.cj.server.auth.dto.RefreshRequest;
import org.cj.server.auth.dto.RegisterRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full-stack auth test: boots the real app (real Postgres, real security filter chain) and
 * drives the endpoints over HTTP via MockMvc. This is the proof that all of M1's pieces —
 * migration, service, JWT, filter, config — actually work together.
 *
 * <p>Emails are randomized per test because the database persists between runs; that keeps
 * these tests independent and re-runnable without cleanup.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    private String uniqueEmail() {
        return "u-" + UUID.randomUUID() + "@example.com";
    }

    private JsonNode register(String email, String password, String name) throws Exception {
        String body = om.writeValueAsString(new RegisterRequest(email, password, name));
        String json = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    @Test
    void registerReturnsTokensAndUserWithoutPasswordHash() throws Exception {
        String email = uniqueEmail();
        String raw = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RegisterRequest(email, "hunter2secret", "Ada"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.name").value("Ada"))
                .andReturn().getResponse().getContentAsString();

        // A password (hash or otherwise) must never appear in any auth response.
        assertThat(raw.toLowerCase()).doesNotContain("password");
    }

    @Test
    void accessTokenUnlocksMe() throws Exception {
        String email = uniqueEmail();
        String accessToken = register(email, "hunter2secret", "Ada").get("accessToken").asText();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("Ada"));
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void meRejectsGarbageToken() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        String email = uniqueEmail();
        register(email, "hunter2secret", "Ada");

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RegisterRequest(email, "another12345", "Ada2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void registerRejectsInvalidPayloadWithFieldErrors() throws Exception {
        // blank name + too-short password + bad email
        String bad = "{\"email\":\"nope\",\"password\":\"short\",\"name\":\"\"}";
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void loginSucceedsWithCorrectPassword() throws Exception {
        String email = uniqueEmail();
        register(email, "hunter2secret", "Ada");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginRequest(email, "hunter2secret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        String email = uniqueEmail();
        register(email, "hunter2secret", "Ada");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new LoginRequest(email, "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenYieldsWorkingAccessToken() throws Exception {
        String email = uniqueEmail();
        String refreshToken = register(email, "hunter2secret", "Ada").get("refreshToken").asText();

        String newAccess = mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String access = om.readTree(newAccess).get("accessToken").asText();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void refreshRejectsAnAccessTokenInPlaceOfRefresh() throws Exception {
        String email = uniqueEmail();
        String accessToken = register(email, "hunter2secret", "Ada").get("accessToken").asText();

        // Sending an ACCESS token to /refresh must be rejected (wrong token type).
        mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RefreshRequest(accessToken))))
                .andExpect(status().isUnauthorized());
    }
}
