package org.cj.server.board.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/cards/{id}/move} — a move expressed as <b>intent</b>, never a
 * rank. The card goes into {@code targetColumnId} (which may be its current column):
 *
 * <ul>
 *   <li>{@code afterCardId} set → immediately after that card;</li>
 *   <li>else {@code beforeCardId} set → immediately before that card;</li>
 *   <li>neither → appended to the end of the column.</li>
 * </ul>
 *
 * If both are sent, {@code afterCardId} wins. Neighbours are resolved against the
 * <em>server's</em> current order, so a slightly stale client still lands a valid,
 * near-intended placement — the server is the ordering authority, and the response carries
 * the canonical {@code rank} to reconcile to.
 */
public record MoveCardRequest(
        @NotNull UUID targetColumnId,
        UUID beforeCardId,
        UUID afterCardId) {
}
