package org.cj.server.board.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.board.entity.BoardColumn;
import org.cj.server.board.entity.Card;
import org.cj.server.board.repository.BoardColumnRepository;
import org.cj.server.board.repository.CardRepository;
import org.cj.server.common.exception.NotFoundException;
import org.cj.server.common.ranking.LexoRank;

/**
 * Card business logic. Like columns, every operation is authorized through
 * {@link BoardService#requireOwnedBoard} against the card's board.
 *
 * <p>Two M2 specifics: new cards are <b>appended</b> within their column via {@link LexoRank},
 * and a card's denormalized {@code boardId} is set from its column at creation — so the
 * board-scoped aggregate read (M2.6) can find every card without joining through columns.
 */
@Service
public class CardService {

    private final CardRepository cards;
    private final BoardColumnRepository columns;
    private final BoardService boardService;

    public CardService(CardRepository cards, BoardColumnRepository columns, BoardService boardService) {
        this.cards = cards;
        this.columns = columns;
        this.boardService = boardService;
    }

    /** Append a card to the end of a column on an owned board. */
    @Transactional
    public Card create(UUID columnId, UUID userId, String title, String description) {
        BoardColumn column = columns.findById(columnId)
                .orElseThrow(() -> new NotFoundException("Column not found"));
        boardService.requireOwnedBoard(column.getBoardId(), userId);

        String lastRank = cards.findFirstByColumnIdOrderByRankDesc(columnId)
                .map(Card::getRank)
                .orElse(null);
        String rank = LexoRank.between(lastRank, null);
        // boardId is taken from the column — keeping the denormalized copy correct by construction.
        return cards.save(Card.create(columnId, column.getBoardId(), title, description, rank));
    }

    @Transactional
    public Card update(UUID cardId, UUID userId, String title, String description) {
        Card card = requireCardOnOwnedBoard(cardId, userId);
        card.edit(title, description);
        return cards.save(card);
    }

    @Transactional
    public void delete(UUID cardId, UUID userId) {
        Card card = requireCardOnOwnedBoard(cardId, userId);
        cards.delete(card);
    }

    /** Load a card and assert the caller owns its board, else 404. */
    private Card requireCardOnOwnedBoard(UUID cardId, UUID userId) {
        Card card = cards.findById(cardId)
                .orElseThrow(() -> new NotFoundException("Card not found"));
        boardService.requireOwnedBoard(card.getBoardId(), userId);
        return card;
    }
}
