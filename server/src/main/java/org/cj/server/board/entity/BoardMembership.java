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
 * Who can access a board and at what role. Maps to {@code board_membership}.
 *
 * <p>In M2 the only rows created are the board owner's own {@code OWNER}/{@code ACTIVE}
 * membership, written alongside the board so that M4 can switch authorization from
 * "owner-only" to "membership-role" with no data backfill. Pending email invites
 * ({@code userId} null, {@code invitedEmail} set) arrive in M4.
 */
@Entity
@Table(name = "board_membership")
public class BoardMembership {

    @Id
    private UUID id;

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    /** Null while an email invite is pending (M4). */
    @Column(name = "user_id")
    private UUID userId;

    /** Set for pending invites before the user exists (M4). */
    @Column(name = "invited_email", length = 255)
    private String invitedEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MembershipStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BoardMembership() {
    }

    private BoardMembership(UUID id, UUID boardId, UUID userId, String invitedEmail,
                           Role role, MembershipStatus status, Instant createdAt) {
        this.id = id;
        this.boardId = boardId;
        this.userId = userId;
        this.invitedEmail = invitedEmail;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** The owner's membership, created with the board: an ACTIVE OWNER tied to a real user. */
    public static BoardMembership createOwner(UUID boardId, UUID userId) {
        return new BoardMembership(UUID.randomUUID(), boardId, userId, null,
                Role.OWNER, MembershipStatus.ACTIVE, Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public UUID getBoardId() {
        return boardId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getInvitedEmail() {
        return invitedEmail;
    }

    public Role getRole() {
        return role;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
