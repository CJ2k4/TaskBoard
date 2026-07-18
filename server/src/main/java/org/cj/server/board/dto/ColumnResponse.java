package org.cj.server.board.dto;

import java.time.Instant;
import java.util.UUID;

import org.cj.server.board.entity.BoardColumn;

/** A column in API responses. {@code rank} is exposed so the client can reconcile ordering. */
public record ColumnResponse(
        UUID id,
        UUID boardId,
        String title,
        String rank,
        Instant createdAt,
        Instant updatedAt) {

    public static ColumnResponse from(BoardColumn column) {
        return new ColumnResponse(
                column.getId(),
                column.getBoardId(),
                column.getTitle(),
                column.getRank(),
                column.getCreatedAt(),
                column.getUpdatedAt());
    }
}
