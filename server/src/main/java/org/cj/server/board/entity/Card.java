package org.cj.server.board.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A card within a column. Maps to {@code card}.
 *
 * <p>Note the two foreign keys: {@code columnId} (its column) and {@code boardId} (its board).
 * {@code boardId} is <b>denormalized</b> — derivable via the column — but kept directly so
 * board-scoped reads and auth checks don't have to join through columns. It must be kept in
 * sync on cross-column moves (M3): the target column must belong to the same board.
 *
 * <p>{@code rank} is a LexoRank string positioning the card within its column.
 */
@Entity
@Table(name = "card")
public class Card {

    @Id
    private UUID id;

    @Column(name = "column_id", nullable = false)
    private UUID columnId;

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(nullable = false, length = 280)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 64)
    private String rank;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Card() {
    }

    private Card(UUID id, UUID columnId, UUID boardId, String title, String description,
                String rank, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.columnId = columnId;
        this.boardId = boardId;
        this.title = title;
        this.description = description;
        this.rank = rank;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A new card in {@code columnId} (belonging to {@code boardId}) at a server-computed rank. */
    public static Card create(UUID columnId, UUID boardId, String title, String description, String rank) {
        Instant now = Instant.now();
        return new Card(UUID.randomUUID(), columnId, boardId, title, description, rank, now, now);
    }

    /** Edit title/description (last-write-wins fields), bumping {@code updatedAt}. */
    public void edit(String title, String description) {
        this.title = title;
        this.description = description;
        this.updatedAt = Instant.now();
    }

    /**
     * Move this card to a column (possibly its current one) at a server-computed rank. A move
     * is a user action, so {@code updatedAt} is bumped. The caller must have verified the
     * target column belongs to this card's board — {@code boardId} does not change.
     */
    public void moveTo(UUID columnId, String rank) {
        this.columnId = columnId;
        this.rank = rank;
        this.updatedAt = Instant.now();
    }

    /**
     * Reassign the rank during a re-balance. Deliberately does <b>not</b> bump
     * {@code updatedAt}: re-spacing is server bookkeeping, not a user edit, and must never
     * win a future last-write-wins conflict (M5) on behalf of an untouched card.
     */
    public void rebalanceRank(String rank) {
        this.rank = rank;
    }

    public UUID getId() {
        return id;
    }

    public UUID getColumnId() {
        return columnId;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getRank() {
        return rank;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
