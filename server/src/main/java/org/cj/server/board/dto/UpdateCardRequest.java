package org.cj.server.board.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/cards/{id}} — edit the last-write-wins fields. All editable fields
 * are sent together; {@code description} and {@code label} may be null to clear them, and
 * {@code assigneeId} may be null to unassign. A non-null {@code assigneeId} must belong to an
 * active member of the card's board (the service rejects anyone else with a 400).
 */
public record UpdateCardRequest(
        @NotBlank @Size(max = 280) String title,
        @Size(max = 5000) String description,
        @Size(max = 40) String label,
        UUID assigneeId) {
}
