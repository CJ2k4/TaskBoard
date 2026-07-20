package org.cj.server.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
 * Full-stack tests for M4.3 role enforcement — the slice that makes membership rows *mean*
 * something. Every case drives the real HTTP endpoints as a real signed-in user, because the
 * thing under test is precisely what the outside world can reach.
 *
 * <p>Two statuses matter and they are not interchangeable:
 * <ul>
 *   <li><b>404</b> — the caller has no membership at all. Indistinguishable from a board that
 *       doesn't exist, on purpose: a 403 here would confirm the board is real.</li>
 *   <li><b>403</b> — the caller <i>is</i> a member, just not a powerful enough one. They already
 *       know the board exists, so the honest error is the useful one.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleEnforcementIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    // ---------------------------------------------------------------- fixtures

    /** A registered user: their access token and email. */
    private record TestUser(String token, String email) {}

    /** A board owned by {@code owner}, already shared with an editor and a viewer. */
    private record SharedBoard(String id, TestUser owner, TestUser editor, TestUser viewer,
                               String columnId, String cardId, String emptyColumnId) {}

    private TestUser newUser() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        String json = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"hunter2secret","name":"Ada"}"""
                                .formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new TestUser(om.readTree(json).get("accessToken").asText(), email);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, TestUser user) {
        return b.header("Authorization", "Bearer " + user.token());
    }

    private JsonNode json(String body) throws Exception {
        return om.readTree(body);
    }

    /**
     * The shared setup: an owner with a board holding one column with a card plus a second,
     * empty column (deleting a non-empty column is a 409 by design, so the "can delete" check
     * needs an empty one), and an EDITOR and a VIEWER invited by email. Both invitees are
     * registered first, so their invites land ACTIVE immediately — the pending path has its own
     * test in {@code InviteResolutionIntegrationTest}.
     */
    private SharedBoard sharedBoard() throws Exception {
        TestUser owner = newUser();
        TestUser editor = newUser();
        TestUser viewer = newUser();

        String boardId = json(mvc.perform(auth(post("/api/boards"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Shared\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String columnId = json(mvc.perform(auth(post("/api/boards/" + boardId + "/columns"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"To Do\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String cardId = json(mvc.perform(auth(post("/api/columns/" + columnId + "/cards"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"First\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String emptyColumnId = json(mvc.perform(auth(post("/api/boards/" + boardId + "/columns"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Empty\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        inviteAs(owner, boardId, editor.email(), "EDITOR");
        inviteAs(owner, boardId, viewer.email(), "VIEWER");

        return new SharedBoard(boardId, owner, editor, viewer, columnId, cardId, emptyColumnId);
    }

    private void inviteAs(TestUser inviter, String boardId, String email, String role) throws Exception {
        mvc.perform(auth(post("/api/boards/" + boardId + "/invites"), inviter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated());
    }

    /**
     * Every content mutation the API offers, as request builders — so a role can be checked
     * against the *whole* write surface rather than a sample of it. Missing one here is exactly
     * how a permission hole ships.
     *
     * <p>Ordered so that running the list straight through against an authorized user succeeds
     * end to end: the card is deleted before the column that would otherwise refuse to go, and
     * the delete targets the deliberately empty column.
     */
    private List<MockHttpServletRequestBuilder> contentMutations(SharedBoard b) {
        List<MockHttpServletRequestBuilder> all = new ArrayList<>();
        all.add(post("/api/boards/" + b.id() + "/columns")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"New\"}"));
        all.add(patch("/api/columns/" + b.columnId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Renamed\"}"));
        all.add(patch("/api/columns/" + b.columnId() + "/move")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        all.add(post("/api/columns/" + b.columnId() + "/cards")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"New card\"}"));
        all.add(patch("/api/cards/" + b.cardId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Edited\"}"));
        all.add(patch("/api/cards/" + b.cardId() + "/move")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetColumnId\":\"" + b.columnId() + "\"}"));
        all.add(delete("/api/cards/" + b.cardId()));
        all.add(delete("/api/columns/" + b.emptyColumnId()));
        return all;
    }

    /** Every board-administration action — the owner-only surface. */
    private List<MockHttpServletRequestBuilder> ownerOnlyActions(SharedBoard b, String membershipId) {
        List<MockHttpServletRequestBuilder> all = new ArrayList<>();
        all.add(patch("/api/boards/" + b.id())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed board\"}"));
        all.add(delete("/api/boards/" + b.id()));
        all.add(post("/api/boards/" + b.id() + "/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"someone-" + UUID.randomUUID() + "@example.com\",\"role\":\"VIEWER\"}"));
        all.add(patch("/api/memberships/" + membershipId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"));
        all.add(delete("/api/memberships/" + membershipId));
        return all;
    }

    /** The membership row of the board member with the given role. */
    private String membershipIdOf(SharedBoard b, String role) throws Exception {
        JsonNode list = json(mvc.perform(auth(get("/api/boards/" + b.id() + "/members"), b.owner()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (JsonNode m : list) {
            if (m.get("role").asText().equals(role)) {
                return m.get("id").asText();
            }
        }
        throw new AssertionError("no " + role + " membership on the board");
    }

    // ---------------------------------------------------------------- editor

    @Test
    void editorCanReadAndMutateBoardContents() throws Exception {
        SharedBoard b = sharedBoard();

        mvc.perform(auth(get("/api/boards/" + b.id()), b.editor())).andExpect(status().isOk());
        for (MockHttpServletRequestBuilder mutation : contentMutations(b)) {
            // 2xx of any shape is a pass here — the point is "not blocked", not the payload.
            mvc.perform(auth(mutation, b.editor()))
                    .andExpect(status().is2xxSuccessful());
        }
    }

    @Test
    void editorCannotAdministerTheBoard() throws Exception {
        SharedBoard b = sharedBoard();
        String viewerMembership = membershipIdOf(b, "VIEWER");

        for (MockHttpServletRequestBuilder action : ownerOnlyActions(b, viewerMembership)) {
            mvc.perform(auth(action, b.editor())).andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------- viewer

    @Test
    void viewerCanReadBoardAndMemberList() throws Exception {
        SharedBoard b = sharedBoard();

        JsonNode board = json(mvc.perform(auth(get("/api/boards/" + b.id()), b.viewer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(board.get("myRole").asText()).isEqualTo("VIEWER");
        assertThat(board.get("columns").size()).isEqualTo(2);

        mvc.perform(auth(get("/api/boards/" + b.id() + "/members"), b.viewer()))
                .andExpect(status().isOk());
    }

    @Test
    void viewerIsReadOnlyAcrossEveryMutation() throws Exception {
        SharedBoard b = sharedBoard();
        String editorMembership = membershipIdOf(b, "EDITOR");

        for (MockHttpServletRequestBuilder mutation : contentMutations(b)) {
            mvc.perform(auth(mutation, b.viewer())).andExpect(status().isForbidden());
        }
        for (MockHttpServletRequestBuilder action : ownerOnlyActions(b, editorMembership)) {
            mvc.perform(auth(action, b.viewer())).andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------- stranger

    @Test
    void strangerGetsNotFoundEverywhere() throws Exception {
        SharedBoard b = sharedBoard();
        TestUser stranger = newUser();
        String editorMembership = membershipIdOf(b, "EDITOR");

        // 404, never 403 — a non-member must not be able to prove the board exists.
        mvc.perform(auth(get("/api/boards/" + b.id()), stranger)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/boards/" + b.id() + "/members"), stranger))
                .andExpect(status().isNotFound());
        for (MockHttpServletRequestBuilder mutation : contentMutations(b)) {
            mvc.perform(auth(mutation, stranger)).andExpect(status().isNotFound());
        }
        for (MockHttpServletRequestBuilder action : ownerOnlyActions(b, editorMembership)) {
            mvc.perform(auth(action, stranger)).andExpect(status().isNotFound());
        }
    }

    // ---------------------------------------------------------------- the board list

    @Test
    void boardListShowsSharedBoardsWithTheCallersRole() throws Exception {
        SharedBoard b = sharedBoard();

        assertThat(roleInBoardList(b.owner(), b.id())).isEqualTo("OWNER");
        assertThat(roleInBoardList(b.editor(), b.id())).isEqualTo("EDITOR");
        assertThat(roleInBoardList(b.viewer(), b.id())).isEqualTo("VIEWER");

        // A stranger's list never contains it.
        assertThat(roleInBoardList(newUser(), b.id())).isNull();
    }

    @Test
    void removingAMemberRevokesTheirAccessImmediately() throws Exception {
        SharedBoard b = sharedBoard();
        String editorMembership = membershipIdOf(b, "EDITOR");

        mvc.perform(auth(delete("/api/memberships/" + editorMembership), b.owner()))
                .andExpect(status().isNoContent());

        assertThat(roleInBoardList(b.editor(), b.id())).isNull();
        // Back to a stranger: the board is not merely off-limits, it's invisible.
        mvc.perform(auth(get("/api/boards/" + b.id()), b.editor())).andExpect(status().isNotFound());
    }

    @Test
    void demotedEditorLosesWriteAccessButKeepsReadAccess() throws Exception {
        SharedBoard b = sharedBoard();
        String editorMembership = membershipIdOf(b, "EDITOR");

        mvc.perform(auth(patch("/api/memberships/" + editorMembership), b.owner())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        mvc.perform(auth(get("/api/boards/" + b.id()), b.editor())).andExpect(status().isOk());
        mvc.perform(auth(post("/api/columns/" + b.columnId() + "/cards"), b.editor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    /** The caller's role on {@code boardId} per {@code GET /api/boards}, or null if absent. */
    private String roleInBoardList(TestUser user, String boardId) throws Exception {
        JsonNode list = json(mvc.perform(auth(get("/api/boards"), user))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (JsonNode board : list) {
            if (board.get("id").asText().equals(boardId)) {
                return board.get("myRole").asText();
            }
        }
        return null;
    }
}
