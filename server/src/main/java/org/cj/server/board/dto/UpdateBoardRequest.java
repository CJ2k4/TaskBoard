package org.cj.server.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/boards/{id}} — edits the board's name and description together
 * (both last-write-wins). {@code description} may be null to clear it; the name stays required.
 */
public record UpdateBoardRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 280) String description) {
}
