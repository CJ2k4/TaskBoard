package org.cj.server.realtime.dto;

import java.time.Instant;
import java.util.UUID;

import org.cj.server.board.dto.BoardEventType;
import org.cj.server.board.service.BoardChangedEvent;

/**
 * The message clients actually receive on {@code /topic/board/{boardId}} — the public wire
 * format of the real-time layer, serialized to JSON by the broker's Jackson converter.
 *
 * <p>It mirrors {@link BoardChangedEvent} rather than reusing it because the two answer to
 * different masters: the domain event is internal and free to change with the services, while
 * this one is a contract with the browser. The {@code at} stamp is added here, at broadcast
 * time.
 */
public record BoardEvent(
        BoardEventType type,
        UUID boardId,
        UUID actorId,
        Instant at,
        Object payload) {

    public static BoardEvent from(BoardChangedEvent event) {
        return new BoardEvent(
                event.type(),
                event.boardId(),
                event.actorId(),
                Instant.now(),
                event.payload());
    }
}
