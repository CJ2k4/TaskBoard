package org.cj.server.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.cj.server.auth.entity.User;
import org.cj.server.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full-stack tests for the M6 shareable invite link. The link is the one sharing path that isn't
 * owner-gated on redeem — holding the token is the authorization — so these lean hardest on the
 * two things that could go wrong: that a stranger with the link joins at exactly the intended
 * role and no higher, and that a link, once disabled, is truly dead.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InviteLinkIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    // ---------------------------------------------------------------- fixtures

    private record TestUser(String id, String token, String email) {}

    private TestUser newUser() {
        String email = uniqueEmail();
        User u = users.save(User.createOAuth(email, "Ada", null));
        return new TestUser(u.getId().toString(), tokenFor(u), email);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, TestUser user) {
        return b.header("Authorization", "Bearer " + user.token());
    }

    private JsonNode json(String body) throws Exception {
        return om.readTree(body);
    }

    private String createBoard(TestUser owner, String name) throws Exception {
        return json(mvc.perform(auth(post("/api/boards"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    /** Create/rotate the link at a role and return its token. */
    private String createLink(TestUser owner, String boardId, String role) throws Exception {
        return json(mvc.perform(auth(post("/api/boards/" + boardId + "/invite-link"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode accept(TestUser caller, String token) throws Exception {
        return json(mvc.perform(auth(post("/api/invite-links/" + token + "/accept"), caller))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private int memberCount(TestUser owner, String boardId) throws Exception {
        return json(mvc.perform(auth(get("/api/boards/" + boardId + "/members"), owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).size();
    }

    // ---------------------------------------------------------------- tests

    @Test
    void redeemingALinkJoinsTheBoardAtTheLinkRole() throws Exception {
        TestUser owner = newUser();
        TestUser joiner = newUser();
        String boardId = createBoard(owner, "Open board");
        String token = createLink(owner, boardId, "EDITOR");

        JsonNode result = accept(joiner, token);
        assertThat(result.get("boardId").asText()).isEqualTo(boardId);
        assertThat(result.get("role").asText()).isEqualTo("EDITOR");

        // They're a real member now: they can load the board, and the roster shows them as EDITOR.
        JsonNode board = json(mvc.perform(auth(get("/api/boards/" + boardId), joiner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(board.get("myRole").asText()).isEqualTo("EDITOR");
        assertThat(memberCount(owner, boardId)).isEqualTo(2); // owner + joiner
    }

    @Test
    void redeemingTwiceIsIdempotent() throws Exception {
        TestUser owner = newUser();
        TestUser joiner = newUser();
        String boardId = createBoard(owner, "Open board");
        String token = createLink(owner, boardId, "VIEWER");

        accept(joiner, token);
        JsonNode second = accept(joiner, token);
        assertThat(second.get("role").asText()).isEqualTo("VIEWER");

        // Still just the two of them — the second redeem added no duplicate membership.
        assertThat(memberCount(owner, boardId)).isEqualTo(2);
    }

    @Test
    void theOwnerRedeemingTheirOwnLinkStaysOwner() throws Exception {
        TestUser owner = newUser();
        String boardId = createBoard(owner, "Open board");
        String token = createLink(owner, boardId, "EDITOR");

        // A no-op: the owner is already the most privileged member; the link can't demote them.
        JsonNode result = accept(owner, token);
        assertThat(result.get("role").asText()).isEqualTo("OWNER");
        assertThat(memberCount(owner, boardId)).isEqualTo(1);
    }

    @Test
    void aDisabledLinkNoLongerResolves() throws Exception {
        TestUser owner = newUser();
        TestUser joiner = newUser();
        String boardId = createBoard(owner, "Open board");
        String token = createLink(owner, boardId, "EDITOR");

        mvc.perform(auth(delete("/api/boards/" + boardId + "/invite-link"), owner))
                .andExpect(status().isNoContent());

        // The URL someone copied earlier is now dead.
        mvc.perform(auth(post("/api/invite-links/" + token + "/accept"), joiner))
                .andExpect(status().isNotFound());
        // And the owner's GET reports no active link.
        JsonNode link = json(mvc.perform(auth(get("/api/boards/" + boardId + "/invite-link"), owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(link.get("token").isNull()).isTrue();
    }

    @Test
    void anUnknownTokenIs404() throws Exception {
        TestUser stranger = newUser();
        mvc.perform(auth(post("/api/invite-links/" + UUID.randomUUID() + "/accept"), stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void rotatingTheLinkInvalidatesTheOldToken() throws Exception {
        TestUser owner = newUser();
        TestUser joiner = newUser();
        String boardId = createBoard(owner, "Open board");
        String firstToken = createLink(owner, boardId, "EDITOR");
        String secondToken = createLink(owner, boardId, "EDITOR"); // rotate

        assertThat(secondToken).isNotEqualTo(firstToken);
        // The first URL stops working the moment a new one is minted.
        mvc.perform(auth(post("/api/invite-links/" + firstToken + "/accept"), joiner))
                .andExpect(status().isNotFound());
        accept(joiner, secondToken); // the new one works
    }

    @Test
    void aLinkCannotGrantOwner() throws Exception {
        TestUser owner = newUser();
        String boardId = createBoard(owner, "Open board");
        // A public link that minted owners would be a way to seize a board.
        mvc.perform(auth(post("/api/boards/" + boardId + "/invite-link"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheOwnerCanManageTheLink() throws Exception {
        TestUser owner = newUser();
        TestUser member = newUser();
        String boardId = createBoard(owner, "Open board");
        String token = createLink(owner, boardId, "EDITOR");
        accept(member, token); // now an EDITOR member — but still not the owner

        // A non-owner member: 403 on every management verb (they can see the board, not run it).
        mvc.perform(auth(post("/api/boards/" + boardId + "/invite-link"), member)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/boards/" + boardId + "/invite-link"), member))
                .andExpect(status().isForbidden());
        mvc.perform(auth(delete("/api/boards/" + boardId + "/invite-link"), member))
                .andExpect(status().isForbidden());

        // A total stranger gets 404 — the board doesn't reveal itself to non-members.
        TestUser stranger = newUser();
        mvc.perform(auth(get("/api/boards/" + boardId + "/invite-link"), stranger))
                .andExpect(status().isNotFound());
    }
}
