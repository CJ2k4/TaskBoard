package org.cj.server.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.cj.server.board.repository.CardRepository;
import org.cj.server.board.service.BinPurgeJob;
import org.cj.server.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The card bin: deleting a card hides it from the board but keeps it restorable, and the
 * retention job is what finally removes it.
 *
 * <p>The purge is driven by calling {@link BinPurgeJob#purgeExpired()} directly with a card
 * back-dated in the database, rather than by waiting — the schedule is Spring's concern, the
 * cutoff arithmetic is ours, and only the latter is worth a test.
 */
class CardBinIntegrationTest extends IntegrationTest {

    @Autowired
    CardRepository cards;

    @Autowired
    BinPurgeJob purgeJob;

    @Autowired
    JdbcTemplate jdbc;

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

    private JsonNode board(String token, String boardId) throws Exception {
        return om.readTree(mvc.perform(auth(get("/api/boards/" + boardId), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode bin(String token, String boardId) throws Exception {
        return om.readTree(mvc.perform(auth(get("/api/boards/" + boardId + "/bin"), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /**
     * Age a binned card by rewriting {@code deleted_at} directly. The entity deliberately has no
     * setter for it — binning always stamps "now" — so the only honest way to simulate the
     * passage of two days is to reach past JPA.
     */
    private void jdbcSetDeletedAt(String cardId, Instant when) {
        jdbc.update("UPDATE card SET deleted_at = ? WHERE id = ?",
                java.sql.Timestamp.from(when), UUID.fromString(cardId));
    }

    @Test
    void deletedCardLeavesTheBoardButAppearsInTheBin() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String columnId = createColumn(token, boardId, "Backlog");
        String cardId = createCard(token, columnId, "Doomed");

        mvc.perform(auth(delete("/api/cards/" + cardId), token)).andExpect(status().isNoContent());

        // Gone from the board aggregate…
        JsonNode columns = board(token, boardId).get("columns");
        assertThat(columns.get(0).get("cards")).isEmpty();

        // …but present in the bin, with the column it came from and when it expires.
        JsonNode binned = bin(token, boardId);
        assertThat(binned).hasSize(1);
        assertThat(binned.get(0).get("card").get("id").asText()).isEqualTo(cardId);
        assertThat(binned.get(0).get("card").get("title").asText()).isEqualTo("Doomed");
        assertThat(binned.get(0).get("columnTitle").asText()).isEqualTo("Backlog");

        Instant deletedAt = Instant.parse(binned.get(0).get("deletedAt").asText());
        Instant purgeAt = Instant.parse(binned.get(0).get("purgeAt").asText());
        assertThat(Duration.between(deletedAt, purgeAt)).isEqualTo(BinPurgeJob.RETENTION);
    }

    @Test
    void restorePutsTheCardBackInItsColumn() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String columnId = createColumn(token, boardId, "Backlog");
        String cardId = createCard(token, columnId, "Saved");

        mvc.perform(auth(delete("/api/cards/" + cardId), token)).andExpect(status().isNoContent());
        mvc.perform(auth(post("/api/cards/" + cardId + "/restore"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cardId))
                .andExpect(jsonPath("$.columnId").value(columnId));

        JsonNode columns = board(token, boardId).get("columns");
        assertThat(columns.get(0).get("cards")).hasSize(1);
        assertThat(bin(token, boardId)).isEmpty();
    }

    @Test
    void restoreAppendsAfterCardsAddedWhileItWasBinned() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String columnId = createColumn(token, boardId, "Backlog");
        String first = createCard(token, columnId, "First");

        mvc.perform(auth(delete("/api/cards/" + first), token)).andExpect(status().isNoContent());
        createCard(token, columnId, "Second");
        mvc.perform(auth(post("/api/cards/" + first + "/restore"), token)).andExpect(status().isOk());

        // The restored card is appended, not slotted back into its old position.
        JsonNode cardsJson = board(token, boardId).get("columns").get(0).get("cards");
        assertThat(cardsJson).hasSize(2);
        assertThat(cardsJson.get(0).get("title").asText()).isEqualTo("Second");
        assertThat(cardsJson.get(1).get("title").asText()).isEqualTo("First");
    }

    @Test
    void aBinnedCardCannotBeEditedOrMoved() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String columnId = createColumn(token, boardId, "Backlog");
        String cardId = createCard(token, columnId, "Gone");

        mvc.perform(auth(delete("/api/cards/" + cardId), token)).andExpect(status().isNoContent());

        // To everything but the bin, a binned card does not exist.
        mvc.perform(auth(patch("/api/cards/" + cardId), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Edited\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(auth(patch("/api/cards/" + cardId + "/move"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetColumnId\":\"" + columnId + "\"}"))
                .andExpect(status().isNotFound());
        // And binning it twice is not a way to reset the clock.
        mvc.perform(auth(delete("/api/cards/" + cardId), token)).andExpect(status().isNotFound());
    }

    @Test
    void restoringACardThatIsNotBinnedIsAConflict() throws Exception {
        String token = newUserToken();
        String columnId = createColumn(token, createBoard(token), "Backlog");
        String cardId = createCard(token, columnId, "Live");

        mvc.perform(auth(post("/api/cards/" + cardId + "/restore"), token))
                .andExpect(status().isConflict());
    }

    @Test
    void columnHoldingBinnedCardsCannotBeDeleted() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String columnId = createColumn(token, boardId, "Backlog");
        String cardId = createCard(token, columnId, "In the bin");

        mvc.perform(auth(delete("/api/cards/" + cardId), token)).andExpect(status().isNoContent());

        // The column looks empty, but dropping it would cascade the binned row away.
        mvc.perform(auth(delete("/api/columns/" + columnId), token))
                .andExpect(status().isConflict());

        // Restoring (or purging) the card releases the column again.
        mvc.perform(auth(post("/api/cards/" + cardId + "/restore"), token)).andExpect(status().isOk());
        mvc.perform(auth(delete("/api/cards/" + cardId), token)).andExpect(status().isNoContent());
        purgeExpiredWithCardAged(cardId, BinPurgeJob.RETENTION.plusHours(1));
        mvc.perform(auth(delete("/api/columns/" + columnId), token)).andExpect(status().isNoContent());
    }

    private void purgeExpiredWithCardAged(String cardId, Duration age) {
        jdbcSetDeletedAt(cardId, Instant.now().minus(age));
        purgeJob.purgeExpired();
    }

    @Test
    void purgeRemovesCardsPastRetentionAndKeepsFresherOnes() throws Exception {
        String token = newUserToken();
        String boardId = createBoard(token);
        String columnId = createColumn(token, boardId, "Backlog");
        String stale = createCard(token, columnId, "Stale");
        String fresh = createCard(token, columnId, "Fresh");

        mvc.perform(auth(delete("/api/cards/" + stale), token)).andExpect(status().isNoContent());
        mvc.perform(auth(delete("/api/cards/" + fresh), token)).andExpect(status().isNoContent());

        // Age only the first past the window — one hour inside it must survive.
        jdbcSetDeletedAt(stale, Instant.now().minus(BinPurgeJob.RETENTION).minusSeconds(3600));
        jdbcSetDeletedAt(fresh, Instant.now().minus(BinPurgeJob.RETENTION).plusSeconds(3600));
        purgeJob.purgeExpired();

        assertThat(cards.findById(UUID.fromString(stale))).isEmpty();
        assertThat(cards.findById(UUID.fromString(fresh))).isPresent();

        JsonNode binned = bin(token, boardId);
        assertThat(binned).hasSize(1);
        assertThat(binned.get(0).get("card").get("title").asText()).isEqualTo("Fresh");
    }

    @Test
    void viewerMayReadTheBinButNotRestore() throws Exception {
        String ownerToken = newUserToken();
        var viewer = createUser();
        String viewerToken = tokenFor(viewer);

        String boardId = createBoard(ownerToken);
        String columnId = createColumn(ownerToken, boardId, "Backlog");
        String cardId = createCard(ownerToken, columnId, "Doomed");

        mvc.perform(auth(post("/api/boards/" + boardId + "/invites"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + viewer.getEmail() + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        mvc.perform(auth(delete("/api/cards/" + cardId), ownerToken)).andExpect(status().isNoContent());

        assertThat(bin(viewerToken, boardId)).hasSize(1);
        mvc.perform(auth(post("/api/cards/" + cardId + "/restore"), viewerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void binIsHiddenFromNonMembers() throws Exception {
        String owner = newUserToken();
        String stranger = newUserToken();
        String boardId = createBoard(owner);

        mvc.perform(auth(get("/api/boards/" + boardId + "/bin"), stranger))
                .andExpect(status().isNotFound());
    }
}
