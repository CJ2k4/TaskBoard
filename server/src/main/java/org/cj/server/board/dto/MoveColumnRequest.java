package org.cj.server.board.dto;

import java.util.UUID;

/**
 * Body of {@code PATCH /api/columns/{id}/move} — reorder a column among its board's columns,
 * expressed as intent (same semantics as {@link MoveCardRequest}, one level up):
 * {@code afterColumnId} → immediately after that column; else {@code beforeColumnId} →
 * immediately before it; neither → append to the end. If both are sent, after wins. Columns
 * never change boards, so there is no target-board field.
 */
public record MoveColumnRequest(
        UUID beforeColumnId,
        UUID afterColumnId) {
}
