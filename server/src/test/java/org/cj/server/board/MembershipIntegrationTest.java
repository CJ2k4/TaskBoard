package org.cj.server.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full-stack tests for the M4.1 sharing endpoints: creating invites (pending vs immediately
 * active), listing members, changing roles, removing members/invites, and the owner-only
 * guard around all of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MembershipIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    /** Register a fresh user; returns [accessToken, email]. */
    private String[] newUser() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        String body = """
                {"email":"%s","password":"hunter2secret","name":"Ada"}""".formatted(email);
        String json = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new String[] {om.readTree(json).get("accessToken").asText(), email};
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

    private JsonNode invite(String token, String boardId, String email, String role, int expect)
            throws Exception {
        String json = mvc.perform(auth(post("/api/boards/" + boardId + "/invites"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().is(expect))
                .andReturn().getResponse().getContentAsString();
        return json.isEmpty() ? om.createObjectNode() : om.readTree(json);
    }

    private JsonNode members(String token, String boardId) throws Exception {
        return om.readTree(mvc.perform(auth(get("/api/boards/" + boardId + "/members"), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void inviteUnknownEmailCreatesPendingInvite() throws Exception {
        String owner = newUser()[0];
        String boardId = createBoard(owner);
        String stranger = "ghost-" + UUID.randomUUID() + "@example.com";

        JsonNode created = invite(owner, boardId, stranger, "EDITOR", 201);
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("invitedEmail").asText()).isEqualTo(stranger);
        assertThat(created.get("userId").isNull()).isTrue();

        // Owner (ACTIVE/OWNER) + the pending invite show in the list, oldest first.
        JsonNode list = members(owner, boardId);
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("role").asText()).isEqualTo("OWNER");
        assertThat(list.get(1).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void inviteNormalizesEmailCase() throws Exception {
        String owner = newUser()[0];
        String boardId = createBoard(owner);
        String weird = "MiXeD-" + UUID.randomUUID() + "@Example.COM";

        JsonNode created = invite(owner, boardId, weird, "VIEWER", 201);
        assertThat(created.get("invitedEmail").asText()).isEqualTo(weird.trim().toLowerCase());
    }

    @Test
    void inviteExistingUserIsImmediatelyActiveWithNameJoined() throws Exception {
        String owner = newUser()[0];
        String[] invitee = newUser(); // already registered
        String boardId = createBoard(owner);

        JsonNode created = invite(owner, boardId, invitee[1], "VIEWER", 201);
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("userId").isNull()).isFalse();
        assertThat(created.get("email").asText()).isEqualTo(invitee[1]);
        assertThat(created.get("name").asText()).isEqualTo("Ada"); // joined from app_user
    }

    @Test
    void duplicateInvitesConflict() throws Exception {
        String owner = newUser()[0];
        String[] member = newUser();
        String boardId = createBoard(owner);
        String ghost = "ghost-" + UUID.randomUUID() + "@example.com";

        invite(owner, boardId, member[1], "EDITOR", 201);
        invite(owner, boardId, member[1], "VIEWER", 409);   // already an active member
        invite(owner, boardId, ghost, "EDITOR", 201);
        invite(owner, boardId, ghost, "EDITOR", 409);       // duplicate pending
    }

    @Test
    void ownerCannotInviteThemselvesOrCreateASecondOwner() throws Exception {
        String[] owner = newUser();
        String boardId = createBoard(owner[0]);

        invite(owner[0], boardId, owner[1], "EDITOR", 409); // already a member (the owner)
        invite(owner[0], boardId, "x-" + UUID.randomUUID() + "@example.com", "OWNER", 400);
    }

    @Test
    void changeRoleFlipsEditorAndViewer() throws Exception {
        String owner = newUser()[0];
        String[] member = newUser();
        String boardId = createBoard(owner);
        String membershipId = invite(owner, boardId, member[1], "EDITOR", 201).get("id").asText();

        mvc.perform(auth(patch("/api/memberships/" + membershipId), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        mvc.perform(auth(patch("/api/memberships/" + membershipId), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest()); // no second owner
    }

    @Test
    void ownerRowCannotBeChangedOrRemoved() throws Exception {
        String owner = newUser()[0];
        String boardId = createBoard(owner);
        String ownerMembershipId = members(owner, boardId).get(0).get("id").asText();

        mvc.perform(auth(patch("/api/memberships/" + ownerMembershipId), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(delete("/api/memberships/" + ownerMembershipId), owner))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removeMemberAndRevokePendingInvite() throws Exception {
        String owner = newUser()[0];
        String[] member = newUser();
        String boardId = createBoard(owner);
        String activeId = invite(owner, boardId, member[1], "EDITOR", 201).get("id").asText();
        String pendingId = invite(owner, boardId, "ghost-" + UUID.randomUUID() + "@example.com",
                "VIEWER", 201).get("id").asText();
        assertThat(members(owner, boardId).size()).isEqualTo(3);

        mvc.perform(auth(delete("/api/memberships/" + activeId), owner)).andExpect(status().isNoContent());
        mvc.perform(auth(delete("/api/memberships/" + pendingId), owner)).andExpect(status().isNoContent());
        assertThat(members(owner, boardId).size()).isEqualTo(1); // just the owner again
    }

    @Test
    void nonOwnerGets404OnEverySharingRoute() throws Exception {
        String owner = newUser()[0];
        String outsider = newUser()[0];
        String boardId = createBoard(owner);
        String membershipId = members(owner, boardId).get(0).get("id").asText();

        invite(outsider, boardId, "x-" + UUID.randomUUID() + "@example.com", "EDITOR", 404);
        mvc.perform(auth(get("/api/boards/" + boardId + "/members"), outsider))
                .andExpect(status().isNotFound());
        mvc.perform(auth(patch("/api/memberships/" + membershipId), outsider)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(delete("/api/memberships/" + membershipId), outsider))
                .andExpect(status().isNotFound());
    }

    @Test
    void inviteRejectsMalformedEmail() throws Exception {
        String owner = newUser()[0];
        String boardId = createBoard(owner);
        invite(owner, boardId, "not-an-email", "EDITOR", 400);
    }
}
