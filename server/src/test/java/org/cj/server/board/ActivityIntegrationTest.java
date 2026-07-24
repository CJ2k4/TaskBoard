package org.cj.server.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.cj.server.auth.entity.User;
import org.cj.server.support.IntegrationTest;
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
 * Full-stack tests for the M6 activity log. They drive the real endpoints and then read
 * {@code GET /api/boards/{id}/activity}, so what's asserted is exactly what a member's feed would
 * show — including that the log is written as a side effect of the mutation, through the
 * BEFORE_COMMIT recorder, with no endpoint of its own on the write path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    // ---------------------------------------------------------------- fixtures

    private record TestUser(String token, String email) {}

    private TestUser newUser() {
        String email = uniqueEmail();
        User u = users.save(User.createOAuth(email, "Ada", null));
        return new TestUser(tokenFor(u), email);
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

    private String createColumn(TestUser owner, String boardId, String title) throws Exception {
        return json(mvc.perform(auth(post("/api/boards/" + boardId + "/columns"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String createCard(TestUser owner, String columnId, String title) throws Exception {
        return json(mvc.perform(auth(post("/api/columns/" + columnId + "/cards"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private void invite(TestUser owner, String boardId, String email, String role) throws Exception {
        mvc.perform(auth(post("/api/boards/" + boardId + "/invites"), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated());
    }

    private JsonNode activity(TestUser caller, String boardId) throws Exception {
        return json(mvc.perform(auth(get("/api/boards/" + boardId + "/activity"), caller))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<String> summaries(JsonNode activity) {
        List<String> out = new ArrayList<>();
        activity.forEach(a -> out.add(a.get("summary").asText()));
        return out;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void recordsEachChangeWithARenderedSummary() throws Exception {
        TestUser owner = newUser();
        String boardId = createBoard(owner, "Roadmap");
        String columnId = createColumn(owner, boardId, "To Do");
        String cardId = createCard(owner, columnId, "Design homepage");

        mvc.perform(auth(patch("/api/cards/" + cardId + "/move"), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetColumnId\":\"" + columnId + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(auth(patch("/api/boards/" + boardId), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Q3 Roadmap\"}"))
                .andExpect(status().isOk());

        JsonNode activity = activity(owner, boardId);

        // Four mutations, four entries. (Board *creation* isn't itself a BoardChangedEvent, so the
        // log starts at the first change made to the board.)
        assertThat(activity).hasSize(4);
        // Newest first — the board rename was the last thing that happened.
        assertThat(activity.get(0).get("type").asText()).isEqualTo("BOARD_UPDATED");
        assertThat(activity.get(0).get("summary").asText()).isEqualTo("renamed the board to \"Q3 Roadmap\"");
        // The actor's name is joined at read time, not stored in the summary.
        assertThat(activity.get(0).get("actorName").asText()).isEqualTo("Ada");

        // The full set, regardless of any same-instant ordering wobble among the earlier three.
        assertThat(summaries(activity)).containsExactlyInAnyOrder(
                "added column \"To Do\"",
                "added card \"Design homepage\"",
                "moved card \"Design homepage\"",
                "renamed the board to \"Q3 Roadmap\"");
    }

    @Test
    void memberEventsAreLoggedByHandle() throws Exception {
        TestUser owner = newUser();
        TestUser editor = newUser();
        String boardId = createBoard(owner, "Team");
        invite(owner, boardId, editor.email(), "EDITOR");

        JsonNode activity = activity(owner, boardId);
        // The invitee registered first, so the invite lands ACTIVE and the summary uses their name.
        assertThat(activity.get(0).get("type").asText()).isEqualTo("MEMBER_ADDED");
        assertThat(activity.get(0).get("summary").asText()).isEqualTo("invited Ada");
    }

    @Test
    void aViewerCanReadTheLog() throws Exception {
        TestUser owner = newUser();
        TestUser viewer = newUser();
        String boardId = createBoard(owner, "Shared");
        createColumn(owner, boardId, "To Do");
        invite(owner, boardId, viewer.email(), "VIEWER");

        // Reading the log needs only VIEWER — a viewer sees the history like any member.
        JsonNode activity = activity(viewer, boardId);
        assertThat(activity.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aNonMemberGets404() throws Exception {
        TestUser owner = newUser();
        TestUser stranger = newUser();
        String boardId = createBoard(owner, "Private");
        createColumn(owner, boardId, "To Do");

        // Same 404 the board itself would give a non-member: the log leaks neither existence nor
        // contents.
        mvc.perform(auth(get("/api/boards/" + boardId + "/activity"), stranger))
                .andExpect(status().isNotFound());
    }
}
