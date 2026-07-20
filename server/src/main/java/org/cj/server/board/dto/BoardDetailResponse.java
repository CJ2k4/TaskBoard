package org.cj.server.board.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cj.server.board.entity.Board;
import org.cj.server.board.entity.BoardColumn;
import org.cj.server.board.entity.Card;
import org.cj.server.board.entity.Role;

/**
 * A whole board in one response: the board plus its columns (in rank order), each with its
 * cards (in rank order). This is the "load the board" read the frontend uses to render
 * everything at once — including {@code myRole}, the caller's role, so the page knows whether
 * to render editable or read-only before the user touches anything.
 */
public record BoardDetailResponse(
        UUID id,
        String name,
        UUID ownerId,
        Role myRole,
        Instant createdAt,
        Instant updatedAt,
        List<ColumnWithCards> columns) {

    /** One column together with its ordered cards. */
    public record ColumnWithCards(ColumnResponse column, List<CardResponse> cards) {
    }

    /**
     * Assemble the nested view. {@code cards} is expected to be the whole board's cards already
     * sorted by rank; grouping by column preserves that order, so each column's cards come out
     * rank-ordered without a second sort.
     */
    public static BoardDetailResponse of(Board board, List<BoardColumn> columns, List<Card> cards,
                                         Role myRole) {
        Map<UUID, List<CardResponse>> byColumn = new LinkedHashMap<>();
        for (Card card : cards) {
            byColumn.computeIfAbsent(card.getColumnId(), k -> new ArrayList<>())
                    .add(CardResponse.from(card));
        }
        List<ColumnWithCards> nested = columns.stream()
                .map(col -> new ColumnWithCards(
                        ColumnResponse.from(col),
                        byColumn.getOrDefault(col.getId(), List.of())))
                .toList();
        return new BoardDetailResponse(
                board.getId(),
                board.getName(),
                board.getOwnerId(),
                myRole,
                board.getCreatedAt(),
                board.getUpdatedAt(),
                nested);
    }
}
