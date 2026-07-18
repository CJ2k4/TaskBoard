package org.cj.server.board.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A column on a board (e.g. "To do"). Maps to {@code board_column} — the table is named that
 * because {@code COLUMN} is a SQL reserved word; the Java type is {@code BoardColumn} to avoid
 * clashing with JPA's own {@code @Column} annotation.
 *
 * <p>{@code rank} is a LexoRank string giving this column's position among the board's columns
 * (see {@code common/ranking/LexoRank}); columns are always loaded ordered by it. The server
 * assigns the rank — clients never supply one.
 */
@Entity
@Table(name = "board_column")
public class BoardColumn {

    @Id
    private UUID id;

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 64)
    private String rank;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BoardColumn() {
    }

    private BoardColumn(UUID id, UUID boardId, String title, String rank,
                       Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.boardId = boardId;
        this.title = title;
        this.rank = rank;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A new column on {@code boardId} at the given server-computed {@code rank}. */
    public static BoardColumn create(UUID boardId, String title, String rank) {
        Instant now = Instant.now();
        return new BoardColumn(UUID.randomUUID(), boardId, title, rank, now, now);
    }

    public void rename(String title) {
        this.title = title;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public String getTitle() {
        return title;
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
