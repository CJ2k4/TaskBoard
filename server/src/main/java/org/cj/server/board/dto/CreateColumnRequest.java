package org.cj.server.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/boards/{boardId}/columns}. */
public record CreateColumnRequest(@NotBlank @Size(max = 160) String title) {
}
