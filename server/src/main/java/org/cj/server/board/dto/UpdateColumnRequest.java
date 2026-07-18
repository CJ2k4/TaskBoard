package org.cj.server.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code PATCH /api/columns/{id}} — M2 only supports renaming (moves are M3). */
public record UpdateColumnRequest(@NotBlank @Size(max = 160) String title) {
}
