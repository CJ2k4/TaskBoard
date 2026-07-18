package org.cj.server.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/boards}. */
public record CreateBoardRequest(@NotBlank @Size(max = 160) String name) {
}
