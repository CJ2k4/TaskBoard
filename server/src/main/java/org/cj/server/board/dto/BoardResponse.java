package org.cj.server.board.dto;

import java.time.Instant;
import java.util.UUID;

import org.cj.server.board.entity.Board;
import org.cj.server.board.entity.Role;

/**
 * A board in list/summary responses. {@code myRole} is the <em>caller's</em> role on this
 * board, not a property of the board itself — it's what lets the client decide up front
 * whether to offer editing controls, rather than inferring it from failed requests.
 */
public record BoardResponse(
        UUID id,
        String name,
        String description,
        UUID ownerId,
        Role myRole,
        Instant createdAt,
        Instant updatedAt) {

    public static BoardResponse from(Board board, Role myRole) {
        return new BoardResponse(
                board.getId(),
                board.getName(),
                board.getDescription(),
                board.getOwnerId(),
                myRole,
                board.getCreatedAt(),
                board.getUpdatedAt());
    }

    /** Convenience for the list endpoint, whose query already pairs each board with its role. */
    public static BoardResponse from(BoardWithRole withRole) {
        return from(withRole.board(), withRole.role());
    }
}
