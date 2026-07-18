package org.cj.server.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/cards/{id}} — edit title/description (M2; moves are M3). Both
 * editable fields are sent together; {@code description} may be null to clear it.
 */
public record UpdateCardRequest(
        @NotBlank @Size(max = 280) String title,
        @Size(max = 5000) String description) {
}
