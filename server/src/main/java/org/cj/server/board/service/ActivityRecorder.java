package org.cj.server.board.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.cj.server.board.dto.BoardEventType;
import org.cj.server.board.dto.BoardSummary;
import org.cj.server.board.dto.CardResponse;
import org.cj.server.board.dto.ColumnResponse;
import org.cj.server.board.dto.MembershipResponse;
import org.cj.server.board.entity.BoardActivity;
import org.cj.server.board.repository.BoardActivityRepository;

/**
 * Writes the activity log (M6) off the very same {@link BoardChangedEvent} the real-time layer
 * broadcasts — so the services stay ignorant of it, exactly as they're ignorant of WebSockets,
 * and the dependency arrow is one-way into {@code board}.
 *
 * <p><b>{@code BEFORE_COMMIT}, deliberately — the opposite of the broadcaster.</b>
 * {@code BoardEventBroadcaster} waits for {@code AFTER_COMMIT} because a message about a change
 * that later rolled back would be a lie on the wire. Persisting the log entry is the reverse
 * concern: it must be part of the same atomic unit as the change it records, committing (or
 * rolling back) with it. {@code BEFORE_COMMIT} runs inside the still-open transaction, so this
 * {@code save} joins it. (Both share the corollary that an event published outside any transaction
 * is silently dropped — every publisher is a {@code @Transactional} service method.)
 *
 * <p>Not everything is logged. {@code BOARD_DELETED} is skipped — the board (and its cascading
 * activity rows) is on its way out, so recording its own demise is pointless and would fight the
 * cascade. {@code PRESENCE} never reaches here at all: it isn't a {@code BoardChangedEvent}. Rank
 * re-balances are likewise absent, because they never publish an event — the log inherits that
 * silence for free.
 */
@Component
public class ActivityRecorder {

    private final BoardActivityRepository activities;

    public ActivityRecorder(BoardActivityRepository activities) {
        this.activities = activities;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onBoardChanged(BoardChangedEvent event) {
        String summary = summarize(event.type(), event.payload());
        if (summary == null) {
            return; // BOARD_DELETED (and, in principle, PRESENCE) are not recorded
        }
        activities.save(BoardActivity.create(event.boardId(), event.actorId(), event.type(), summary));
    }

    /**
     * The human-readable predicate for an event — the actor's name is prepended at read time, so
     * this is just "{verb} {object}". Payload shape is fixed by {@code type} (see
     * {@link BoardEventType}), which makes the casts safe. Returns null for events we don't log.
     */
    private String summarize(BoardEventType type, Object payload) {
        return switch (type) {
            case CARD_CREATED -> "added card " + quote(((CardResponse) payload).title());
            case CARD_UPDATED -> "edited card " + quote(((CardResponse) payload).title());
            case CARD_MOVED -> "moved card " + quote(((CardResponse) payload).title());
            case CARD_DELETED -> "deleted a card";

            case COLUMN_CREATED -> "added column " + quote(((ColumnResponse) payload).title());
            case COLUMN_UPDATED -> "renamed column " + quote(((ColumnResponse) payload).title());
            case COLUMN_MOVED -> "moved column " + quote(((ColumnResponse) payload).title());
            case COLUMN_DELETED -> "deleted a column";

            case BOARD_UPDATED -> "renamed the board to " + quote(((BoardSummary) payload).name());

            case MEMBER_ADDED -> "invited " + who((MembershipResponse) payload);
            case MEMBER_UPDATED -> {
                MembershipResponse m = (MembershipResponse) payload;
                yield "changed " + who(m) + "'s role to " + m.role();
            }
            case MEMBER_REMOVED -> "removed " + who((MembershipResponse) payload);

            case BOARD_DELETED, PRESENCE -> null;
        };
    }

    /** A member's display handle: their name, else the pending-invite email, else their email. */
    private String who(MembershipResponse m) {
        if (m.name() != null) {
            return m.name();
        }
        return m.invitedEmail() != null ? m.invitedEmail() : m.email();
    }

    private String quote(String s) {
        return "\"" + s + "\"";
    }
}
