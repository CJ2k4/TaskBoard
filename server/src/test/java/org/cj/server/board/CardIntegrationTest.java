package org.cj.server.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/**
 * Full-stack card CRUD test. Appends yield increasing ranks and the correct denormalized
 * boardId; edits persist; access is scoped to the owning board.
 */
class CardIntegrationTest extends IntegrationTest {


    private String createBoard(String token) throws Exception {
        return om.readTree(mvc.perform(auth(post("/api/boards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Board\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String createColumn(String token, String boardId) throws Exception {
        return om.readTree(mvc.perform(auth(post("/api/boards/" + boardId + "/columns"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"To do\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode createCard(String token, String columnId, String title) throws Exception {
        return om.readTree(mvc.perform(auth(post("/api/columns/" + columnId + "/cards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void appendsHaveIncreasingRanksAndCorrectBoardId() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String columnId = createColumn(token, boardId);

        JsonNode c1 = createCard(token, columnId, "First");
        JsonNode c2 = createCard(token, columnId, "Second");

        assertThat(c1.get("rank").asText()).isLessThan(c2.get("rank").asText());
        assertThat(c1.get("boardId").asText()).isEqualTo(boardId);
        assertThat(c1.get("columnId").asText()).isEqualTo(columnId);
    }

    @Test
    void updatePersistsTitleAndDescription() throws Exception {
        String token = newUserToken();
        String columnId = createColumn(token, createBoard(token));
        String cardId = createCard(token, columnId, "Draft").get("id").asText();

        mvc.perform(auth(patch("/api/cards/" + cardId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Final\",\"description\":\"the details\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Final"))
                .andExpect(jsonPath("$.description").value("the details"));
    }

    @Test
    void deleteSucceeds() throws Exception {
        String token = newUserToken();
        String columnId = createColumn(token, createBoard(token));
        String cardId = createCard(token, columnId, "Doomed").get("id").asText();

        mvc.perform(auth(delete("/api/cards/" + cardId), token)).andExpect(status().isNoContent());
    }

    @Test
    void nonOwnerCannotCreateOrMutateCards() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String columnId = createColumn(alice, createBoard(alice));
        String cardId = createCard(alice, columnId, "Alice's").get("id").asText();

        mvc.perform(auth(post("/api/columns/" + columnId + "/cards"), bob)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Intruder\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(patch("/api/cards/" + cardId), bob)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Hacked\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(delete("/api/cards/" + cardId), bob)).andExpect(status().isNotFound());
    }

    @Test
    void createInMissingColumnIs404() throws Exception {
        String token = newUserToken();
        mvc.perform(auth(post("/api/columns/" + UUID.randomUUID() + "/cards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Ghost\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRejectsBlankTitle() throws Exception {
        String token = newUserToken();
        String columnId = createColumn(token, createBoard(token));
        mvc.perform(auth(post("/api/columns/" + columnId + "/cards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
