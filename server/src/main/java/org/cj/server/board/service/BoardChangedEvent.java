package org.cj.server.board.service;

import java.util.UUID;

import org.cj.server.board.dto.BoardEventType;

/**
 * "Something on this board changed." Published by the board services inside their write
 * transaction; consumed by the real-time layer, which turns it into a STOMP message on
 * {@code /topic/board/{boardId}} once the transaction has actually committed.
 *
 * <p>The indirection is the point. The services do not know that WebSockets exist — they
 * announce a domain fact and move on — so the dependency arrow runs {@code realtime → board}
 * only, exactly as {@code board → auth} does for {@code UserSignedInEvent}. Deleting the
 * whole {@code realtime} package would leave the REST API working.
 *
 * @param boardId whose topic the event belongs on — always the board, even for a card event,
 *                because the board is the unit of subscription
 * @param actorId the user who made the change. Broadcast back to everyone including them, so
 *                a client that already applied the change optimistically can recognize its own
 *                echo and skip re-applying it
 * @param type    what happened; also fixes the shape of {@code payload} (see {@link BoardEventType})
 * @param payload the after-state DTO, or a {@link org.cj.server.board.dto.DeletedRef} for
 *                deletions. Typed as {@code Object} because one event class carries every
 *                shape; {@code type} is the discriminator that makes it safe to read
 */
public record BoardChangedEvent(UUID boardId, UUID actorId, BoardEventType type, Object payload) {
}
