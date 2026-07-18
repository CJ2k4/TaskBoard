package org.cj.server.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/columns/{columnId}/cards}. {@code description} is optional (the DB
 * column is nullable); the size cap just guards against unbounded input.
 */
public record CreateCardRequest(
        @NotBlank @Size(max = 280) String title,
        @Size(max = 5000) String description) {
}
