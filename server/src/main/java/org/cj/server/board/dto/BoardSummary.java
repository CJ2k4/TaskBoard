package org.cj.server.board.dto;

import java.time.Instant;
import java.util.UUID;

import org.cj.server.board.entity.Board;

/**
 * A board's own fields, with no caller in the picture. This is deliberately <b>not</b>
 * {@link BoardResponse}: that record carries {@code myRole}, which is a fact about whoever
 * asked, and a broadcast has no single asker. Sending a role here would mean sending one
 * member's permissions to every member.
 */
public record BoardSummary(
        UUID id,
        String name,
        String description,
        UUID ownerId,
        Instant createdAt,
        Instant updatedAt) {

    public static BoardSummary from(Board board) {
        return new BoardSummary(
                board.getId(),
                board.getName(),
                board.getDescription(),
                board.getOwnerId(),
                board.getCreatedAt(),
                board.getUpdatedAt());
    }
}
