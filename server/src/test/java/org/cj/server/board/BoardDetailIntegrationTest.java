package org.cj.server.board;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Full-stack test of the whole-board aggregate read {@code GET /api/boards/{id}}: columns and
 * cards come back nested and in rank order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BoardDetailIntegrationTest {

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

    private String id(String json) throws Exception {
        return om.readTree(json).get("id").asText();
    }

    private String createBoard(String token) throws Exception {
        return id(mvc.perform(auth(post("/api/boards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Board\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String createColumn(String token, String boardId, String title) throws Exception {
        return id(mvc.perform(auth(post("/api/boards/" + boardId + "/columns"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void createCard(String token, String columnId, String title) throws Exception {
        mvc.perform(auth(post("/api/columns/" + columnId + "/cards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void returnsColumnsAndCardsNestedInRankOrder() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String todo = createColumn(token, boardId, "To do");
        String done = createColumn(token, boardId, "Done");
        createCard(token, todo, "First");
        createCard(token, todo, "Second");
        createCard(token, done, "Shipped");

        mvc.perform(auth(get("/api/boards/" + boardId), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(2))
                // columns in rank (creation) order
                .andExpect(jsonPath("$.columns[0].column.title").value("To do"))
                .andExpect(jsonPath("$.columns[1].column.title").value("Done"))
                // each column's cards in rank order
                .andExpect(jsonPath("$.columns[0].cards.length()").value(2))
                .andExpect(jsonPath("$.columns[0].cards[0].title").value("First"))
                .andExpect(jsonPath("$.columns[0].cards[1].title").value("Second"))
                .andExpect(jsonPath("$.columns[1].cards.length()").value(1))
                .andExpect(jsonPath("$.columns[1].cards[0].title").value("Shipped"));
    }

    @Test
    void emptyBoardReturnsEmptyColumns() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);

        mvc.perform(auth(get("/api/boards/" + boardId), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Board"))
                .andExpect(jsonPath("$.columns.length()").value(0));
    }
}
