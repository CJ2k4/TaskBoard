package org.cj.server.board.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A board — the top of the graph (board → columns → cards). Maps to the {@code board} table
 * (see {@code V3__board_graph.sql}).
 *
 * <p>Follows the entity conventions set by {@code auth/entity/User}: app-generated {@code UUID}
 * key (no {@code @GeneratedValue}), a {@code create(...)} factory that stamps timestamps, and
 * {@code Instant} (UTC) times. Foreign keys are held as plain {@code UUID} fields
 * ({@code ownerId}) rather than {@code @ManyToOne} associations — simpler, no lazy-loading
 * surprises, and it matches the denormalized-id style the schema uses elsewhere.
 */
@Entity
@Table(name = "board")
public class Board {

    @Id
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Bumped on every mutation; the last-write-wins key for real-time conflict resolution (M5). */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Board() {
    }

    private Board(UUID id, String name, UUID ownerId, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A brand-new board owned by {@code ownerId}. Generates the id and stamps both timestamps. */
    public static Board create(String name, UUID ownerId) {
        Instant now = Instant.now();
        return new Board(UUID.randomUUID(), name, ownerId, now, now);
    }

    /** Rename the board, bumping {@code updatedAt}. */
    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
