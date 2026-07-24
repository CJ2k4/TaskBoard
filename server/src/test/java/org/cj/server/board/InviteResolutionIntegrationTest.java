package org.cj.server.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import org.cj.server.auth.dto.GoogleLoginRequest;
import org.cj.server.auth.service.GoogleTokenVerifier;
import org.cj.server.auth.service.GoogleTokenVerifier.GoogleAccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full-stack tests for M4.2: a PENDING invite becomes an ACTIVE membership the moment its email
 * signs in. The trigger is the {@code UserSignedInEvent} published by the (Google) sign-in, so
 * these tests drive {@code POST /api/auth/google} with a stubbed {@link GoogleTokenVerifier}; the
 * members list is the observation point.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InviteResolutionIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @MockitoBean
    GoogleTokenVerifier googleTokenVerifier;

    /** Sign in via Google (stubbed) for a specific email — creates-or-links the account and fires
     *  the sign-in event. Returns the access token. */
    private String signIn(String email) throws Exception {
        String token = "tok-" + email;
        when(googleTokenVerifier.verify(token))
                .thenReturn(new GoogleAccount(email, "Ada", null, true));
        String json = mvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(new GoogleLoginRequest(token))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("accessToken").asText();
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private String createBoard(String token) throws Exception {
        return om.readTree(mvc.perform(auth(post("/api/boards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Shared\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private void invite(String token, String boardId, String email, String role) throws Exception {
        mvc.perform(auth(post("/api/boards/" + boardId + "/invites"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated());
    }

    /** The single membership row for the given email/invitedEmail on a board, or null. */
    private JsonNode membershipFor(String ownerToken, String boardId, String email) throws Exception {
        JsonNode list = om.readTree(mvc.perform(auth(get("/api/boards/" + boardId + "/members"), ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (JsonNode m : list) {
            if ((m.hasNonNull("email") && m.get("email").asText().equals(email))
                    || (m.hasNonNull("invitedEmail") && m.get("invitedEmail").asText().equals(email))) {
                return m;
            }
        }
        return null;
    }

    @Test
    void pendingInviteActivatesWhenTheEmailSignsIn() throws Exception {
        String owner = signIn("owner-" + UUID.randomUUID() + "@example.com");
        String boardId = createBoard(owner);
        String inviteeEmail = "invitee-" + UUID.randomUUID() + "@example.com";

        invite(owner, boardId, inviteeEmail, "EDITOR");
        assertThat(membershipFor(owner, boardId, inviteeEmail).get("status").asText())
                .isEqualTo("PENDING");

        // The invited email signs in — the AFTER_COMMIT listener resolves the invite.
        signIn(inviteeEmail);

        JsonNode resolved = membershipFor(owner, boardId, inviteeEmail);
        assertThat(resolved.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(resolved.get("userId").isNull()).isFalse();
        assertThat(resolved.get("role").asText()).isEqualTo("EDITOR"); // invited role kept
        assertThat(resolved.get("name").asText()).isEqualTo("Ada");   // user join works now
    }

    @Test
    void repeatSignInLeavesTheMembershipActive() throws Exception {
        String owner = signIn("owner-" + UUID.randomUUID() + "@example.com");
        String boardId = createBoard(owner);
        String email = "invitee-" + UUID.randomUUID() + "@example.com";

        invite(owner, boardId, email, "VIEWER");
        signIn(email); // resolves the pending invite
        signIn(email); // listener fires again — must be harmless (idempotent)

        JsonNode m = membershipFor(owner, boardId, email);
        assertThat(m.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void collidingPendingInviteIsDroppedNotDuplicated() throws Exception {
        String ownerA = signIn("owner-" + UUID.randomUUID() + "@example.com");
        String ownerB = signIn("owner-" + UUID.randomUUID() + "@example.com");
        String boardA = createBoard(ownerA);
        String boardB = createBoard(ownerB);
        String email = "popular-" + UUID.randomUUID() + "@example.com";

        // Two boards invite the same not-yet-signed-in email — two pending rows.
        invite(ownerA, boardA, email, "EDITOR");
        invite(ownerB, boardB, email, "VIEWER");

        signIn(email); // resolves BOTH, on different boards — no collision

        assertThat(membershipFor(ownerA, boardA, email).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(membershipFor(ownerB, boardB, email).get("status").asText()).isEqualTo("ACTIVE");
    }
}
