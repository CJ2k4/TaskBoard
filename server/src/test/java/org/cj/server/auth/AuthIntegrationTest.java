package org.cj.server.auth;

import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.cj.server.auth.dto.GoogleLoginRequest;
import org.cj.server.auth.dto.RefreshRequest;
import org.cj.server.auth.service.GoogleTokenVerifier;
import org.cj.server.auth.service.GoogleTokenVerifier.GoogleAccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Token-lifecycle integration test: boots the real app (real Postgres, real security filter
 * chain) and drives the auth endpoints over HTTP. Since sign-in is Google-only, tokens are
 * obtained by exchanging a (stubbed) Google ID token; from there we exercise refresh rotation,
 * {@code /api/me}, and the removal of the old password endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @MockitoBean
    GoogleTokenVerifier googleTokenVerifier;

    private String uniqueEmail() {
        return "u-" + UUID.randomUUID() + "@example.com";
    }

    /** Sign in via Google (stubbed verifier) and return the AuthResponse JSON. */
    private JsonNode signIn(String email) throws Exception {
        String token = "tok-" + email;
        when(googleTokenVerifier.verify(token))
                .thenReturn(new GoogleAccount(email, "Ada", null, true));
        String json = mvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new GoogleLoginRequest(token))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    @Test
    void googleSignInReturnsTokensAndUserWithoutPasswordHash() throws Exception {
        String email = uniqueEmail();
        JsonNode res = signIn(email);

        org.assertj.core.api.Assertions.assertThat(res.get("accessToken").asText()).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(res.get("refreshToken").asText()).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(res.get("user").get("email").asText()).isEqualTo(email);
        org.assertj.core.api.Assertions.assertThat(res.toString().toLowerCase()).doesNotContain("password");
    }

    @Test
    void accessTokenUnlocksMe() throws Exception {
        String email = uniqueEmail();
        String accessToken = signIn(email).get("accessToken").asText();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
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
    void refreshTokenYieldsWorkingAccessToken() throws Exception {
        String email = uniqueEmail();
        String refreshToken = signIn(email).get("refreshToken").asText();

        String body = mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String access = om.readTree(body).get("accessToken").asText();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void refreshRejectsAnAccessTokenInPlaceOfRefresh() throws Exception {
        String accessToken = signIn(uniqueEmail()).get("accessToken").asText();

        mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RefreshRequest(accessToken))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRejectsGarbage() throws Exception {
        mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new RefreshRequest("not-a-real-token"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordEndpointsAreGone() throws Exception {
        // register/login were removed — the paths no longer map to a handler, so they can never
        // succeed (they issue no token). We assert "not a 2xx" rather than an exact error code.
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"whatever12\",\"name\":\"x\"}"))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .isGreaterThanOrEqualTo(400));
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"whatever12\"}"))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .isGreaterThanOrEqualTo(400));
    }
}
