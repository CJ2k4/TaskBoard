package org.cj.server.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.cj.server.board.entity.Card;
import org.cj.server.board.repository.CardRepository;
import org.cj.server.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Full-stack column CRUD test. Appends produce strictly increasing ranks; deleting a non-empty
 * column is blocked; access is scoped to the owning board. The non-empty case seeds a card
 * directly via the repository, since card endpoints arrive in the next step.
 */
class ColumnIntegrationTest extends IntegrationTest {

    @Autowired
    CardRepository cards;

    private String createBoard(String token) throws Exception {
        String json = mvc.perform(auth(post("/api/boards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Board\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }

    private JsonNode createColumn(String token, String boardId, String title) throws Exception {
        String json = mvc.perform(auth(post("/api/boards/" + boardId + "/columns"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json);
    }

    @Test
    void appendsProduceStrictlyIncreasingRanks() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);

        String r1 = createColumn(token, boardId, "To do").get("rank").asText();
        String r2 = createColumn(token, boardId, "Doing").get("rank").asText();
        String r3 = createColumn(token, boardId, "Done").get("rank").asText();

        assertThat(r1).isLessThan(r2);
        assertThat(r2).isLessThan(r3);
    }

    @Test
    void renamePersists() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String columnId = createColumn(token, boardId, "Old").get("id").asText();

        mvc.perform(auth(patch("/api/columns/" + columnId), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"New\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New"));
    }

    @Test
    void deleteEmptyColumnSucceeds() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String columnId = createColumn(token, boardId, "Empty").get("id").asText();

        mvc.perform(auth(delete("/api/columns/" + columnId), token)).andExpect(status().isNoContent());
    }

    @Test
    void deleteNonEmptyColumnIsBlocked() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        JsonNode column = createColumn(token, boardId, "Has cards");
        UUID columnId = UUID.fromString(column.get("id").asText());
        UUID boardUuid = UUID.fromString(column.get("boardId").asText());

        // Seed a card directly (card endpoints come next step).
        cards.save(Card.create(columnId, boardUuid, "A card", null, "i"));

        mvc.perform(auth(delete("/api/columns/" + columnId), token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void nonOwnerCannotCreateOrMutateColumns() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String boardId = createBoard(alice);
        String columnId = createColumn(alice, boardId, "Alice's").get("id").asText();

        mvc.perform(auth(post("/api/boards/" + boardId + "/columns"), bob)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Intruder\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(patch("/api/columns/" + columnId), bob)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Hacked\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(delete("/api/columns/" + columnId), bob)).andExpect(status().isNotFound());
    }

    @Test
    void createOnMissingBoardIs404() throws Exception {
        String token = newUserToken();
        mvc.perform(auth(post("/api/boards/" + UUID.randomUUID() + "/columns"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Ghost\"}"))
                .andExpect(status().isNotFound());
    }
}
