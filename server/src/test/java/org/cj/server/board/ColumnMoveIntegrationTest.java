package org.cj.server.board;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Full-stack tests for {@code PATCH /api/columns/{id}/move}: reorder before/after/append,
 * anchor validation across boards, and authorization — asserted through the board aggregate.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ColumnMoveIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    private String newUserToken() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        String body = """
                {"email":"%s","password":"hunter2secret","name":"Ada"}""".formatted(email);
        String json = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("accessToken").asText();
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private String createBoard(String token) throws Exception {
        return om.readTree(mvc.perform(auth(post("/api/boards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Board\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String createColumn(String token, String boardId, String title) throws Exception {
        return om.readTree(mvc.perform(auth(post("/api/boards/" + boardId + "/columns"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private void moveColumn(String token, String columnId, String body, int expectStatus) throws Exception {
        mvc.perform(auth(patch("/api/columns/" + columnId + "/move"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectStatus));
    }

    /** Column titles of a board, in the order the aggregate returns them. */
    private List<String> boardOrder(String token, String boardId) throws Exception {
        JsonNode board = om.readTree(mvc.perform(auth(get("/api/boards/" + boardId), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<String> titles = new ArrayList<>();
        board.get("columns").forEach(c -> titles.add(c.get("column").get("title").asText()));
        return titles;
    }

    @Test
    void moveAfterBeforeAndAppendReorderTheBoard() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String a = createColumn(token, boardId, "A");
        String b = createColumn(token, boardId, "B");
        String c = createColumn(token, boardId, "C");
        // start: A B C

        // Move C after A → A C B
        moveColumn(token, c, "{\"afterColumnId\":\"" + a + "\"}", 200);
        assertThat(boardOrder(token, boardId)).containsExactly("A", "C", "B");

        // Move A before B → C A B
        moveColumn(token, a, "{\"beforeColumnId\":\"" + b + "\"}", 200);
        assertThat(boardOrder(token, boardId)).containsExactly("C", "A", "B");

        // Move C with no anchor → append → A B C
        moveColumn(token, c, "{}", 200);
        assertThat(boardOrder(token, boardId)).containsExactly("A", "B", "C");
    }

    @Test
    void moveBeforeTheFirstColumnPrepends() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String a = createColumn(token, boardId, "A");
        String b = createColumn(token, boardId, "B");

        moveColumn(token, b, "{\"beforeColumnId\":\"" + a + "\"}", 200);
        assertThat(boardOrder(token, boardId)).containsExactly("B", "A");
    }

    @Test
    void anchorFromAnotherBoardIs400() throws Exception {
        String token = newUserToken();
        String board1 = createBoard(token);
        String board2 = createBoard(token);
        String a = createColumn(token, board1, "A");
        String other = createColumn(token, board2, "Other");

        moveColumn(token, a, "{\"afterColumnId\":\"" + other + "\"}", 400);
    }

    @Test
    void nonOwnerAndUnknownAre404() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String boardId = createBoard(alice);
        String a = createColumn(alice, boardId, "A");

        moveColumn(bob, a, "{}", 404);
        moveColumn(alice, UUID.randomUUID().toString(), "{}", 404);
    }
}
