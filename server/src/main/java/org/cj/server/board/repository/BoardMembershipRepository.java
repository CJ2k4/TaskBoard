package org.cj.server.board.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cj.server.board.entity.BoardMembership;
import org.cj.server.board.entity.MembershipStatus;

/**
 * Data access for {@link BoardMembership}. As of M4 this is the heart of authorization:
 * "who are you on this board?" is answered by {@link #findByBoardIdAndUserId}.
 */
public interface BoardMembershipRepository extends JpaRepository<BoardMembership, UUID> {

    Optional<BoardMembership> findByBoardIdAndUserId(UUID boardId, UUID userId);

    boolean existsByBoardIdAndUserId(UUID boardId, UUID userId);

    /** A board's full member/invite list, oldest first (owner naturally comes first). */
    List<BoardMembership> findByBoardIdOrderByCreatedAtAsc(UUID boardId);

    /** Duplicate-pending-invite check (backed by the partial unique index in V3). */
    Optional<BoardMembership> findByBoardIdAndInvitedEmailAndStatus(
            UUID boardId, String invitedEmail, MembershipStatus status);

    /** All pending invites addressed to an email — resolved when that email signs in (M4.2). */
    List<BoardMembership> findByInvitedEmailAndStatus(String invitedEmail, MembershipStatus status);

    /** A user's active memberships — the basis of "boards I can see" (M4.3). */
    List<BoardMembership> findByUserIdAndStatus(UUID userId, MembershipStatus status);
}
