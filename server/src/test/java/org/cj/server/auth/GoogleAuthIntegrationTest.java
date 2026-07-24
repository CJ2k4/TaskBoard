package org.cj.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
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
import org.cj.server.auth.service.GoogleTokenVerifier;
import org.cj.server.auth.service.GoogleTokenVerifier.GoogleAccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full-stack test of Google sign-in ({@code POST /api/auth/google}). The one piece we can't
 * exercise for real is Google's token verification — a valid Google ID token can't be minted in
 * a test — so {@link GoogleTokenVerifier} is replaced by a Mockito bean that returns whatever
 * {@link GoogleAccount} the test wants. Everything else (controller, service, find-or-create,
 * JWT issuance, DB) is the real thing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GoogleAuthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @MockitoBean
    GoogleTokenVerifier googleTokenVerifier;

    private String uniqueEmail() {
        return "g-" + UUID.randomUUID() + "@example.com";
    }

    private String googleBody() throws Exception {
        return om.writeValueAsString(new GoogleLoginRequest("any-token-the-stub-ignores"));
    }

    @Test
    void newEmailCreatesOAuthAccountAndReturnsTokens() throws Exception {
        String email = uniqueEmail();
        when(googleTokenVerifier.verify("any-token-the-stub-ignores"))
                .thenReturn(new GoogleAccount(email, "Grace Hopper", "https://pic.example/g.png", true));

        String raw = mvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON).content(googleBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.name").value("Grace Hopper"))
                .andExpect(jsonPath("$.user.imageUrl").value("https://pic.example/g.png"))
                .andReturn().getResponse().getContentAsString();

        // OAuth-only account: no password anywhere in the payload.
        assertThat(raw.toLowerCase()).doesNotContain("password");
    }

    @Test
    void repeatGoogleSignInReturnsSameAccountAndBackfillsAvatar() throws Exception {
        String email = uniqueEmail();

        // First sign-in creates the account (no avatar yet).
        when(googleTokenVerifier.verify("any-token-the-stub-ignores"))
                .thenReturn(new GoogleAccount(email, "Ada", null, true));
        String first = mvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON).content(googleBody()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstId = om.readTree(first).get("user").get("id").asText();

        // Second sign-in with the same email → the SAME account (find-or-create by email),
        // and the avatar is backfilled onto the previously-avatarless account.
        when(googleTokenVerifier.verify("any-token-the-stub-ignores"))
                .thenReturn(new GoogleAccount(email, "Ada", "https://pic.example/ada.png", true));
        String second = mvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON).content(googleBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn().getResponse().getContentAsString();
        JsonNode secondUser = om.readTree(second).get("user");

        assertThat(secondUser.get("id").asText()).isEqualTo(firstId);
        assertThat(secondUser.get("imageUrl").asText()).isEqualTo("https://pic.example/ada.png");
    }

    @Test
    void unverifiedGoogleEmailIsRejected() throws Exception {
        when(googleTokenVerifier.verify("any-token-the-stub-ignores"))
                .thenReturn(new GoogleAccount(uniqueEmail(), "Nobody", null, false));

        mvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON).content(googleBody()))
                .andExpect(status().isUnauthorized());
    }
}
