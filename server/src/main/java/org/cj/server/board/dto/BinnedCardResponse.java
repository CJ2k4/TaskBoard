package org.cj.server.board.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.cj.server.board.entity.Card;

/**
 * A card sitting in a board's bin.
 *
 * <p>It carries the card itself plus the three things the bin UI needs and a live board never
 * does: when it was binned, by whom, and when it stops being restorable. {@code purgeAt} is
 * computed here rather than stored, so the retention window lives in exactly one place
 * ({@code BinPurgeJob.RETENTION}) and a change to it applies to cards already in the bin.
 *
 * <p>{@code columnTitle} is resolved by the service, not the entity — the card only knows its
 * column's id, and the bin should read "was in Backlog" without the client having to join.
 */
public record BinnedCardResponse(
        CardResponse card,
        String columnTitle,
        Instant deletedAt,
        UUID deletedBy,
        Instant purgeAt) {

    public static BinnedCardResponse from(Card card, String columnTitle, Duration retention) {
        return new BinnedCardResponse(
                CardResponse.from(card),
                columnTitle,
                card.getDeletedAt(),
                card.getDeletedBy(),
                card.getDeletedAt().plus(retention));
    }
}
