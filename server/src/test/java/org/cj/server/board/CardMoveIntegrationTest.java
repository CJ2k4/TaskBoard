package org.cj.server.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import org.cj.server.board.entity.Card;
import org.cj.server.board.repository.CardRepository;

/**
 * Full-stack tests for {@code PATCH /api/cards/{id}/move}: intent resolution (after / before /
 * append), cross-column moves, validation, authorization, and the rank-exhaustion re-balance —
 * all asserted through the board aggregate, i.e. the order a client would actually see.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CardMoveIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper om;

    @Autowired
    CardRepository cardRepository;

    // --- helpers (register → board → columns → cards, like CardIntegrationTest) ---

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

    private String createCard(String token, String columnId, String title) throws Exception {
        return om.readTree(mvc.perform(auth(post("/api/columns/" + columnId + "/cards"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode moveCard(String token, String cardId, String body, int expectStatus) throws Exception {
        String json = mvc.perform(auth(patch("/api/cards/" + cardId + "/move"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectStatus))
                .andReturn().getResponse().getContentAsString();
        return json.isEmpty() ? om.createObjectNode() : om.readTree(json);
    }

    /** Card titles of one column, in the order the board aggregate returns them. */
    private List<String> columnOrder(String token, String boardId, String columnId) throws Exception {
        JsonNode board = om.readTree(mvc.perform(auth(get("/api/boards/" + boardId), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<String> titles = new ArrayList<>();
        for (JsonNode col : board.get("columns")) {
            if (col.get("column").get("id").asText().equals(columnId)) {
                col.get("cards").forEach(c -> titles.add(c.get("title").asText()));
            }
        }
        return titles;
    }

    // --- intent resolution within one column ---

    @Test
    void moveAfterAndBeforeAndAppendWithinAColumn() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String col = createColumn(token, boardId, "To do");
        String a = createCard(token, col, "A");
        String b = createCard(token, col, "B");
        String c = createCard(token, col, "C");
        // start: A B C

        // Move C after A → A C B
        moveCard(token, c, "{\"targetColumnId\":\"" + col + "\",\"afterCardId\":\"" + a + "\"}", 200);
        assertThat(columnOrder(token, boardId, col)).containsExactly("A", "C", "B");

        // Move A before B (i.e. between C and B) → C A B
        moveCard(token, a, "{\"targetColumnId\":\"" + col + "\",\"beforeCardId\":\"" + b + "\"}", 200);
        assertThat(columnOrder(token, boardId, col)).containsExactly("C", "A", "B");

        // Move C with no anchor → append → A B C
        moveCard(token, c, "{\"targetColumnId\":\"" + col + "\"}", 200);
        assertThat(columnOrder(token, boardId, col)).containsExactly("A", "B", "C");
    }

    @Test
    void moveBeforeTheFirstCardPrepends() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String col = createColumn(token, boardId, "To do");
        String a = createCard(token, col, "A");
        String b = createCard(token, col, "B");

        moveCard(token, b, "{\"targetColumnId\":\"" + col + "\",\"beforeCardId\":\"" + a + "\"}", 200);
        assertThat(columnOrder(token, boardId, col)).containsExactly("B", "A");
    }

    // --- cross-column moves ---

    @Test
    void crossColumnMoveUpdatesColumnIdAndKeepsBoardId() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String todo = createColumn(token, boardId, "To do");
        String done = createColumn(token, boardId, "Done");
        String a = createCard(token, todo, "A");
        String x = createCard(token, done, "X");

        // Move A into Done, before X.
        JsonNode moved = moveCard(token, a,
                "{\"targetColumnId\":\"" + done + "\",\"beforeCardId\":\"" + x + "\"}", 200);

        assertThat(moved.get("columnId").asText()).isEqualTo(done);
        assertThat(moved.get("boardId").asText()).isEqualTo(boardId); // denormalized id intact
        assertThat(columnOrder(token, boardId, todo)).isEmpty();
        assertThat(columnOrder(token, boardId, done)).containsExactly("A", "X");
    }

    @Test
    void moveIntoAnEmptyColumnWorks() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String todo = createColumn(token, boardId, "To do");
        String empty = createColumn(token, boardId, "Empty");
        String a = createCard(token, todo, "A");

        moveCard(token, a, "{\"targetColumnId\":\"" + empty + "\"}", 200);
        assertThat(columnOrder(token, boardId, empty)).containsExactly("A");
    }

    // --- validation & authorization ---

    @Test
    void neighbourInADifferentColumnIs400() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String todo = createColumn(token, boardId, "To do");
        String done = createColumn(token, boardId, "Done");
        String a = createCard(token, todo, "A");
        String x = createCard(token, done, "X");

        // Anchor X lives in Done, but the target is To do — stale/invalid intent.
        moveCard(token, a, "{\"targetColumnId\":\"" + todo + "\",\"afterCardId\":\"" + x + "\"}", 400);
    }

    @Test
    void targetColumnOnAnotherBoardIs400() throws Exception {
        String token = newUserToken();
        String board1 = createBoard(token);
        String board2 = createBoard(token);
        String col1 = createColumn(token, board1, "On board 1");
        String col2 = createColumn(token, board2, "On board 2");
        String a = createCard(token, col1, "A");

        moveCard(token, a, "{\"targetColumnId\":\"" + col2 + "\"}", 400);
    }

    @Test
    void unknownIdsAre404AndMissingTargetIs400() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String col = createColumn(token, boardId, "To do");
        String a = createCard(token, col, "A");

        moveCard(token, UUID.randomUUID().toString(),
                "{\"targetColumnId\":\"" + col + "\"}", 404);                    // unknown card
        moveCard(token, a,
                "{\"targetColumnId\":\"" + UUID.randomUUID() + "\"}", 404);      // unknown column
        moveCard(token, a, "{}", 400);                                            // @NotNull target
    }

    @Test
    void nonOwnerCannotMoveCards() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String boardId = createBoard(alice);
        String col = createColumn(alice, boardId, "To do");
        String a = createCard(alice, col, "A");

        moveCard(bob, a, "{\"targetColumnId\":\"" + col + "\"}", 404);
    }

    // --- rank-exhaustion re-balance ---

    @Test
    void exhaustedGapTriggersRebalanceAndMoveStillLands() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String src = createColumn(token, boardId, "Source");
        String dst = createColumn(token, boardId, "Target");
        String moving = createCard(token, src, "Moving");
        String first = createCard(token, dst, "First");
        String second = createCard(token, dst, "Second");

        // Sabotage the target gap: make First/Second lexicographically adjacent at the length
        // cap, so between() must throw RankExhaustedException on the first attempt.
        Card firstCard = cardRepository.findById(UUID.fromString(first)).orElseThrow();
        Card secondCard = cardRepository.findById(UUID.fromString(second)).orElseThrow();
        firstCard.rebalanceRank("a");
        secondCard.rebalanceRank("a" + "0".repeat(46) + "1");
        cardRepository.saveAll(List.of(firstCard, secondCard));

        // Drive the real endpoint: move Moving between First and Second.
        moveCard(token, moving,
                "{\"targetColumnId\":\"" + dst + "\",\"afterCardId\":\"" + first + "\"}", 200);

        // The move landed in the intended spot…
        assertThat(columnOrder(token, boardId, dst)).containsExactly("First", "Moving", "Second");
        // …and the re-balance re-spaced every rank in the column back to short keys.
        for (String id : List.of(first, moving, second)) {
            Card card = cardRepository.findById(UUID.fromString(id)).orElseThrow();
            assertThat(card.getRank().length()).isLessThanOrEqualTo(4);
        }
    }
}
