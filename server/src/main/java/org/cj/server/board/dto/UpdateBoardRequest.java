package org.cj.server.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code PATCH /api/boards/{id}} — M2 only supports renaming. */
public record UpdateBoardRequest(@NotBlank @Size(max = 160) String name) {
}
