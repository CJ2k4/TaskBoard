package org.cj.server.board.dto;

import java.time.Instant;
import java.util.UUID;

import org.cj.server.board.entity.Board;

/** A board in list/summary responses. */
public record BoardResponse(
        UUID id,
        String name,
        UUID ownerId,
        Instant createdAt,
        Instant updatedAt) {

    public static BoardResponse from(Board board) {
        return new BoardResponse(
                board.getId(),
                board.getName(),
                board.getOwnerId(),
                board.getCreatedAt(),
                board.getUpdatedAt());
    }
}
