package org.cj.server.realtime.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import org.cj.server.board.service.BoardChangedEvent;
import org.cj.server.realtime.dto.BoardEvent;

/**
 * The bridge from domain change to live message: listens for {@link BoardChangedEvent} and
 * pushes it onto that board's topic, where every subscribed member is waiting.
 *
 * <p><b>{@code AFTER_COMMIT} is the whole design.</b> The services publish their event inside
 * the write transaction, but this listener deliberately waits for that transaction to commit
 * before broadcasting. Announcing earlier would mean announcing a change that can still be
 * rolled back — subscribers would render a card that never existed, and (worse) a client that
 * reacted by refetching the board could read the pre-change state and "correct" itself back to
 * stale data. The same trick, for the same reason, as {@code PendingInviteResolver}.
 *
 * <p>The consequence of the default {@code fallbackExecution = false} is worth knowing: an
 * event published outside any transaction is silently dropped. That's acceptable because every
 * publisher is a {@code @Transactional} service method, and the alternative — firing for
 * unmanaged callers — would quietly reintroduce the announce-before-commit bug.
 *
 * <p>No transaction of its own, unlike the invite resolver: this listener only reads the event
 * and writes to a socket, so there's no database work needing a fresh transaction.
 */
@Component
public class BoardEventBroadcaster {

    /** Board topic pattern; the {@code {boardId}} suffix is what makes fan-out per-board. */
    private static final String TOPIC_PREFIX = "/topic/board/";

    private final SimpMessagingTemplate messaging;

    public BoardEventBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBoardChanged(BoardChangedEvent event) {
        messaging.convertAndSend(TOPIC_PREFIX + event.boardId(), BoardEvent.from(event));
    }
}
