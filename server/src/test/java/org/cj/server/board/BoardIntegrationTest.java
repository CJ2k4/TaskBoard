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

import org.cj.server.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.cj.server.board.repository.BoardMembershipRepository;

/**
 * Full-stack board CRUD test: boots the real app + Postgres and drives {@code /api/boards} over
 * HTTP with a real access token obtained by registering. Also asserts the owner's membership
 * row is created and cleaned up, since M4's authorization will depend on it.
 */
class BoardIntegrationTest extends IntegrationTest {

    @Autowired
    BoardMembershipRepository memberships;


    private JsonNode createBoard(String token, String name) throws Exception {
        String json = mvc.perform(auth(post("/api/boards"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    @Test
    void createReturnsBoardAndOwnerMembership() throws Exception {
        String token = newUserToken();

        JsonNode board = createBoard(token, "Roadmap");
        UUID boardId = UUID.fromString(board.get("id").asText());
        UUID ownerId = UUID.fromString(board.get("ownerId").asText());

        assertThat(board.get("name").asText()).isEqualTo("Roadmap");
        // The owner's membership row must be written alongside the board.
        assertThat(memberships.existsByBoardIdAndUserId(boardId, ownerId)).isTrue();
    }

    @Test
    void listReturnsOnlyMyBoards() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        createBoard(alice, "Alice A");
        createBoard(alice, "Alice B");
        createBoard(bob, "Bob A");

        mvc.perform(auth(get("/api/boards"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void anotherUserCannotSeeOrMutateMyBoard() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String boardId = createBoard(alice, "Private").get("id").asText();

        // Bob is a stranger to this board — every route reads as 404 (existence hidden).
        mvc.perform(auth(get("/api/boards/" + boardId), bob)).andExpect(status().isNotFound());
        mvc.perform(auth(patch("/api/boards/" + boardId), bob)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(delete("/api/boards/" + boardId), bob)).andExpect(status().isNotFound());
    }

    @Test
    void renamePersists() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token, "Old name").get("id").asText();

        mvc.perform(auth(patch("/api/boards/" + boardId), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New name"));

        mvc.perform(auth(get("/api/boards/" + boardId), token))
                .andExpect(jsonPath("$.name").value("New name"));
    }

    @Test
    void deleteRemovesBoardAndCascadesMembership() throws Exception {
        String token = newUserToken();
        JsonNode board = createBoard(token, "Doomed");
        UUID boardId = UUID.fromString(board.get("id").asText());
        UUID ownerId = UUID.fromString(board.get("ownerId").asText());

        mvc.perform(auth(delete("/api/boards/" + boardId), token)).andExpect(status().isNoContent());

        mvc.perform(auth(get("/api/boards/" + boardId), token)).andExpect(status().isNotFound());
        // Cascade: the owner membership row is gone too.
        assertThat(memberships.existsByBoardIdAndUserId(boardId, ownerId)).isFalse();
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/boards")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRejectsBlankName() throws Exception {
        String token = newUserToken();
        mvc.perform(auth(post("/api/boards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }
}
