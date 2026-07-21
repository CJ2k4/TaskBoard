package org.cj.server.board.entity;

import java.time.Instant;
import java.util.UUID;

import org.cj.server.board.dto.BoardEventType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One line in a board's activity log (M6): "who did what, when". Append-only — there is no
 * rename or move; a row is written once and only ever read back. Maps to {@code board_activity}
 * (see {@code V4__board_activity.sql}).
 *
 * <p>{@code type} is the {@link BoardEventType} that produced this entry, stored as a string.
 * (This is the one place an entity references the {@code dto} enum — it's a persisted discriminator
 * here, the same value the wire event carries, and duplicating it would only invite drift.)
 * {@code summary} is the rendered predicate without the actor's name — the name is joined from
 * {@code app_user} at read time via {@code actorId}, so it tracks a later rename and degrades to
 * "someone" if the account is gone ({@code actorId} is nullable, SET NULL on user deletion).
 */
@Entity
@Table(name = "board_activity")
public class BoardActivity {

    @Id
    private UUID id;

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BoardEventType type;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BoardActivity() {
    }

    private BoardActivity(UUID id, UUID boardId, UUID actorId, BoardEventType type,
                          String summary, Instant createdAt) {
        this.id = id;
        this.boardId = boardId;
        this.actorId = actorId;
        this.type = type;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    /** A new log entry, stamped now. {@code actorId} may be null only in principle — the actor of
     *  a change is always an authenticated user. */
    public static BoardActivity create(UUID boardId, UUID actorId, BoardEventType type, String summary) {
        return new BoardActivity(UUID.randomUUID(), boardId, actorId, type, summary, Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public BoardEventType getType() {
        return type;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
