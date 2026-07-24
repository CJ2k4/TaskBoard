package org.cj.server.board.dto;

import java.time.Instant;
import java.util.UUID;

import org.cj.server.board.entity.Card;

/** A card in API responses. Exposes both {@code columnId} and the denormalized {@code boardId}. */
public record CardResponse(
        UUID id,
        UUID columnId,
        UUID boardId,
        String title,
        String description,
        String label,
        UUID assigneeId,
        String rank,
        Instant createdAt,
        Instant updatedAt) {

    public static CardResponse from(Card card) {
        return new CardResponse(
                card.getId(),
                card.getColumnId(),
                card.getBoardId(),
                card.getTitle(),
                card.getDescription(),
                card.getLabel(),
                card.getAssigneeId(),
                card.getRank(),
                card.getCreatedAt(),
                card.getUpdatedAt());
    }
}
