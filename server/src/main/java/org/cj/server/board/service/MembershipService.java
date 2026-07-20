package org.cj.server.board.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.auth.entity.User;
import org.cj.server.auth.repository.UserRepository;
import org.cj.server.board.dto.BoardEventType;
import org.cj.server.board.dto.CreateInviteRequest;
import org.cj.server.board.dto.MembershipResponse;
import org.cj.server.board.dto.UpdateMembershipRequest;
import org.cj.server.board.entity.BoardMembership;
import org.cj.server.board.entity.MembershipStatus;
import org.cj.server.board.entity.Role;
import org.cj.server.board.repository.BoardMembershipRepository;
import org.cj.server.common.exception.ConflictException;
import org.cj.server.common.exception.NotFoundException;

/**
 * Invites and member management (M4): creating, listing, re-roling, and removing
 * {@link BoardMembership} rows — the data the role-enforcement guard (M4.3) reads.
 *
 * <p>All mutations are owner-only; reading the member list needs only VIEWER. Authorization
 * always goes through {@link BoardService#requireBoardAccess} so there stays exactly one
 * access-check code path in the app.
 */
@Service
public class MembershipService {

    private final BoardMembershipRepository memberships;
    private final UserRepository users;
    private final BoardService boardService;
    private final ApplicationEventPublisher events;

    public MembershipService(BoardMembershipRepository memberships, UserRepository users,
                             BoardService boardService, ApplicationEventPublisher events) {
        this.memberships = memberships;
        this.users = users;
        this.boardService = boardService;
        this.events = events;
    }

    /**
     * Invite an email to a board (owner only). Two outcomes:
     * <ul>
     *   <li>the email already has an account → an <b>ACTIVE</b> membership for that user,
     *       effective immediately;</li>
     *   <li>no account yet → a <b>PENDING</b> invite that activates when the email signs in
     *       (M4.2).</li>
     * </ul>
     * Duplicates (already a member — including the owner themselves — or an identical pending
     * invite) are 409s. {@code role} must be EDITOR or VIEWER: a board has exactly one owner.
     */
    @Transactional
    public MembershipResponse invite(UUID boardId, UUID callerId, CreateInviteRequest req) {
        boardService.requireBoardAccess(boardId, callerId, Role.OWNER);
        if (req.role() == Role.OWNER) {
            throw new IllegalArgumentException("Cannot invite as OWNER; a board has exactly one owner");
        }
        // Same normalization as registration, so pending invites match at sign-in.
        String email = req.email().trim().toLowerCase(Locale.ROOT);

        User existing = users.findByEmail(email).orElse(null);
        if (existing != null) {
            if (memberships.existsByBoardIdAndUserId(boardId, existing.getId())) {
                throw new ConflictException("Already a member of this board");
            }
            BoardMembership m = memberships.save(
                    BoardMembership.inviteActive(boardId, existing.getId(), req.role()));
            return announce(MembershipResponse.from(m, existing), callerId, BoardEventType.MEMBER_ADDED);
        }

        if (memberships.findByBoardIdAndInvitedEmailAndStatus(boardId, email, MembershipStatus.PENDING)
                .isPresent()) {
            throw new ConflictException("An invite for this email is already pending");
        }
        BoardMembership m = memberships.save(BoardMembership.invitePending(boardId, email, req.role()));
        return announce(MembershipResponse.from(m, null), callerId, BoardEventType.MEMBER_ADDED);
    }

    /**
     * A board's members and pending invites, oldest first. Any active member may see who else
     * is on the board — knowing your collaborators isn't a privileged action; changing them is.
     */
    @Transactional(readOnly = true)
    public List<MembershipResponse> listMembers(UUID boardId, UUID callerId) {
        boardService.requireBoardAccess(boardId, callerId, Role.VIEWER);
        List<BoardMembership> rows = memberships.findByBoardIdOrderByCreatedAtAsc(boardId);

        // Batch-load the users behind ACTIVE rows (one query, not one per member).
        List<UUID> userIds = rows.stream().map(BoardMembership::getUserId).filter(Objects::nonNull).toList();
        Map<UUID, User> byId = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return rows.stream()
                .map(m -> MembershipResponse.from(m, m.getUserId() != null ? byId.get(m.getUserId()) : null))
                .toList();
    }

    /** Change a member's role, EDITOR ↔ VIEWER (owner only; the OWNER row is untouchable). */
    @Transactional
    public MembershipResponse changeRole(UUID membershipId, UUID callerId, UpdateMembershipRequest req) {
        if (req.role() == Role.OWNER) {
            throw new IllegalArgumentException("Cannot promote to OWNER; a board has exactly one owner");
        }
        BoardMembership m = requireMembershipOnOwnedBoard(membershipId, callerId);
        if (m.getRole() == Role.OWNER) {
            throw new IllegalArgumentException("The owner's membership cannot be changed");
        }
        m.changeRole(req.role());
        BoardMembership saved = memberships.save(m);
        User user = saved.getUserId() != null ? users.findById(saved.getUserId()).orElse(null) : null;
        return announce(MembershipResponse.from(saved, user), callerId, BoardEventType.MEMBER_UPDATED);
    }

    /**
     * Remove a member or revoke a pending invite (owner only; the OWNER row stays).
     *
     * <p>The removed member is very likely subscribed to this board's topic right now, so the
     * MEMBER_REMOVED broadcast carries the whole membership rather than a bare id: it's how
     * they learn — from the {@code userId} — that the person just removed was them, and can
     * leave the board instead of sitting on a view they can no longer refresh.
     */
    @Transactional
    public void remove(UUID membershipId, UUID callerId) {
        BoardMembership m = requireMembershipOnOwnedBoard(membershipId, callerId);
        if (m.getRole() == Role.OWNER) {
            throw new IllegalArgumentException("The owner cannot be removed from their board");
        }
        // Read the user before the delete, while the row is still there to describe.
        User user = m.getUserId() != null ? users.findById(m.getUserId()).orElse(null) : null;
        memberships.delete(m);
        announce(MembershipResponse.from(m, user), callerId, BoardEventType.MEMBER_REMOVED);
    }

    /**
     * Tell the board that its roster changed, and hand the response straight back — so a caller
     * can announce and return in one line without losing sight of what it returns.
     */
    private MembershipResponse announce(MembershipResponse membership, UUID actorId, BoardEventType type) {
        events.publishEvent(new BoardChangedEvent(membership.boardId(), actorId, type, membership));
        return membership;
    }

    /**
     * Load a membership and assert the caller owns the board it belongs to. Managing who has
     * access is an owner-only power — an editor can change the board's contents, never its
     * people. 404 if the row is gone or the caller isn't a member; 403 if they are but aren't
     * the owner.
     */
    private BoardMembership requireMembershipOnOwnedBoard(UUID membershipId, UUID callerId) {
        BoardMembership m = memberships.findById(membershipId)
                .orElseThrow(() -> new NotFoundException("Membership not found"));
        boardService.requireBoardAccess(m.getBoardId(), callerId, Role.OWNER);
        return m;
    }
}
