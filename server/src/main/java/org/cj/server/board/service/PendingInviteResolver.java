package org.cj.server.board.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.cj.server.auth.service.UserSignedInEvent;
import org.cj.server.board.entity.BoardMembership;
import org.cj.server.board.entity.MembershipStatus;
import org.cj.server.board.repository.BoardMembershipRepository;

/**
 * Brings pending invites to life: whenever a user signs in (register or login — auth
 * publishes {@link UserSignedInEvent}), every PENDING invite addressed to their email is
 * resolved — the membership gets their user id and flips to ACTIVE, so the shared board
 * appears in their list immediately.
 *
 * <p>Listening to an event, rather than being called by {@code AuthService}, keeps the
 * package dependencies one-way (board → auth) — auth has no idea this class exists.
 *
 * <p>Transaction choreography matters here. The event is published <em>inside</em> the auth
 * transaction, but on registration the new user row isn't committed yet — a separate
 * transaction inserting a membership that references it would hit a foreign-key violation. So
 * we listen {@code AFTER_COMMIT} (the user now exists for everyone) and open our own
 * {@code REQUIRES_NEW} transaction for the writes, since the original transaction is already
 * closed. This combination also works for login, whose read-only transaction commits too.
 */
@Component
public class PendingInviteResolver {

    private final BoardMembershipRepository memberships;

    public PendingInviteResolver(BoardMembershipRepository memberships) {
        this.memberships = memberships;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserSignedIn(UserSignedInEvent event) {
        List<BoardMembership> pending =
                memberships.findByInvitedEmailAndStatus(event.email(), MembershipStatus.PENDING);
        for (BoardMembership invite : pending) {
            // Rare collision: the user already got a membership on this board through another
            // route (e.g. invited by email while pending, then invited again after they
            // registered). The UNIQUE(board_id, user_id) constraint forbids a second row, so
            // the stale pending invite is simply dropped.
            if (memberships.existsByBoardIdAndUserId(invite.getBoardId(), event.userId())) {
                memberships.delete(invite);
            } else {
                invite.activate(event.userId());
                memberships.save(invite);
            }
        }
    }
}
