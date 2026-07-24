package org.cj.server.board.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** A short optional description, shown on dashboard overview cards. */
    @Column(length = 280)
    private String description;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Bumped on every mutation; the last-write-wins key for real-time conflict resolution (M5). */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The current shareable invite link's token (M6), or null when no link is active. */
    @Column(name = "invite_token")
    private UUID inviteToken;

    /** The role that redeeming the link grants — EDITOR or VIEWER, never OWNER. Null with no link. */
    @Enumerated(EnumType.STRING)
    @Column(name = "invite_link_role", length = 16)
    private Role inviteLinkRole;

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

    /** Edit the board's name and description (last-write-wins), bumping {@code updatedAt}. */
    public void edit(String name, String description) {
        this.name = name;
        this.description = description;
        this.updatedAt = Instant.now();
    }

    /**
     * Issue (or rotate) the shareable invite link at {@code role}, minting a fresh token so any
     * previously-shared link stops working. Deliberately does <b>not</b> bump {@code updatedAt}:
     * the link is owner-only side metadata, not board content, and isn't part of the last-write-
     * wins field-edit race.
     */
    public void setInviteLink(Role role) {
        this.inviteToken = UUID.randomUUID();
        this.inviteLinkRole = role;
    }

    /** Disable the invite link — an outstanding URL immediately stops resolving. */
    public void clearInviteLink() {
        this.inviteToken = null;
        this.inviteLinkRole = null;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
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

    public UUID getInviteToken() {
        return inviteToken;
    }

    public Role getInviteLinkRole() {
        return inviteLinkRole;
    }
}
