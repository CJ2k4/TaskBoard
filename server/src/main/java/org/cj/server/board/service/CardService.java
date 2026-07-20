package org.cj.server.board.service;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.board.dto.BoardEventType;
import org.cj.server.board.dto.CardResponse;
import org.cj.server.board.dto.DeletedRef;
import org.cj.server.board.dto.MoveCardRequest;
import org.cj.server.board.entity.BoardColumn;
import org.cj.server.board.entity.Card;
import org.cj.server.board.entity.Role;
import org.cj.server.board.repository.BoardColumnRepository;
import org.cj.server.board.repository.CardRepository;
import org.cj.server.common.exception.NotFoundException;
import org.cj.server.common.ranking.LexoRank;
import org.cj.server.common.ranking.RankExhaustedException;

/**
 * Card business logic. Like columns, every operation is authorized through
 * {@link BoardService#requireBoardAccess} against the card's board, at {@link Role#EDITOR} —
 * cards are board content, so viewers can read them but never change them.
 *
 * <p>Two M2 specifics: new cards are <b>appended</b> within their column via {@link LexoRank},
 * and a card's denormalized {@code boardId} is set from its column at creation — so the
 * board-scoped aggregate read (M2.6) can find every card without joining through columns.
 *
 * <p>Since M5 every mutation also announces itself with a {@link BoardChangedEvent}, which the
 * real-time layer broadcasts to the board's subscribers once the transaction commits.
 */
@Service
public class CardService {

    private final CardRepository cards;
    private final BoardColumnRepository columns;
    private final BoardService boardService;
    private final ApplicationEventPublisher events;

    public CardService(CardRepository cards, BoardColumnRepository columns, BoardService boardService,
                       ApplicationEventPublisher events) {
        this.cards = cards;
        this.columns = columns;
        this.boardService = boardService;
        this.events = events;
    }

    /** Append a card to the end of a column on a board the caller can edit. */
    @Transactional
    public Card create(UUID columnId, UUID userId, String title, String description) {
        BoardColumn column = columns.findById(columnId)
                .orElseThrow(() -> new NotFoundException("Column not found"));
        boardService.requireBoardAccess(column.getBoardId(), userId, Role.EDITOR);

        String lastRank = cards.findFirstByColumnIdOrderByRankDesc(columnId)
                .map(Card::getRank)
                .orElse(null);
        String rank = LexoRank.between(lastRank, null);
        // boardId is taken from the column — keeping the denormalized copy correct by construction.
        Card saved = cards.save(Card.create(columnId, column.getBoardId(), title, description, rank));
        announce(saved, userId, BoardEventType.CARD_CREATED);
        return saved;
    }

    @Transactional
    public Card update(UUID cardId, UUID userId, String title, String description) {
        Card card = requireCardOnBoard(cardId, userId, Role.EDITOR);
        card.edit(title, description);
        Card saved = cards.save(card);
        announce(saved, userId, BoardEventType.CARD_UPDATED);
        return saved;
    }

    @Transactional
    public void delete(UUID cardId, UUID userId) {
        Card card = requireCardOnBoard(cardId, userId, Role.EDITOR);
        cards.delete(card);
        events.publishEvent(new BoardChangedEvent(
                card.getBoardId(), userId, BoardEventType.CARD_DELETED, new DeletedRef(card.getId())));
    }

    /**
     * Move a card — the M3 drag-and-drop write. The request names a target column and (at
     * most) a neighbour; the <b>server</b> resolves that intent against its own current order
     * and computes the canonical rank (see {@link MoveCardRequest} for the semantics).
     *
     * <p>If the gap the card must land in has been subdivided to exhaustion,
     * {@link LexoRank#between} throws and we <b>re-balance</b>: every sibling in the target
     * column gets a fresh short key from {@link LexoRank#spread} (order unchanged), then the
     * placement is retried once against the roomy new gaps.
     */
    @Transactional
    public Card move(UUID cardId, UUID userId, MoveCardRequest req) {
        Card card = requireCardOnBoard(cardId, userId, Role.EDITOR);

        BoardColumn target = columns.findById(req.targetColumnId())
                .orElseThrow(() -> new NotFoundException("Column not found"));
        // Cross-board moves are not a thing: the denormalized card.boardId stays correct
        // precisely because we refuse any target outside the card's own board.
        if (!target.getBoardId().equals(card.getBoardId())) {
            throw new IllegalArgumentException("Target column belongs to a different board");
        }

        // The card's future siblings, in current rank order, minus the card itself (it may
        // already live in the target column when reordering in place).
        List<Card> siblings = cards.findByColumnIdOrderByRankAsc(target.getId()).stream()
                .filter(c -> !c.getId().equals(card.getId()))
                .toList();

        Placement placement = resolvePlacement(siblings, req.afterCardId(), req.beforeCardId());
        String rank;
        try {
            rank = LexoRank.between(placement.prev(), placement.next());
        } catch (RankExhaustedException ex) {
            rebalance(siblings);
            // Ranks changed under the placement — resolve again, then retry once.
            placement = resolvePlacement(siblings, req.afterCardId(), req.beforeCardId());
            rank = LexoRank.between(placement.prev(), placement.next());
        }

        card.moveTo(target.getId(), rank);
        Card saved = cards.save(card);
        // Only the moved card is announced, even when the re-balance above rewrote every
        // sibling's rank. Subscribers holding the old ranks still sort into the same order —
        // spread() re-spaces without reordering — and they never compute placements locally
        // anyway: a move is intent the server resolves. Broadcasting the re-space would be
        // churn nobody reads.
        announce(saved, userId, BoardEventType.CARD_MOVED);
        return saved;
    }

    /** Tell the board that a card changed; the real-time layer relays it after commit. */
    private void announce(Card card, UUID actorId, BoardEventType type) {
        events.publishEvent(new BoardChangedEvent(
                card.getBoardId(), actorId, type, CardResponse.from(card)));
    }

    /** The rank bounds a move must land between; either side may be null (open end). */
    private record Placement(String prev, String next) {}

    /**
     * Turn "(after X | before X | at the end)" into concrete prev/next rank bounds using the
     * server's sibling order. A named neighbour that isn't actually in the target column is a
     * client error (400) — likely a stale or malformed request.
     */
    private Placement resolvePlacement(List<Card> siblings, UUID afterId, UUID beforeId) {
        if (afterId != null) {
            int i = indexOf(siblings, afterId, "afterCardId");
            String next = i + 1 < siblings.size() ? siblings.get(i + 1).getRank() : null;
            return new Placement(siblings.get(i).getRank(), next);
        }
        if (beforeId != null) {
            int i = indexOf(siblings, beforeId, "beforeCardId");
            String prev = i > 0 ? siblings.get(i - 1).getRank() : null;
            return new Placement(prev, siblings.get(i).getRank());
        }
        // No anchor: append to the end.
        String last = siblings.isEmpty() ? null : siblings.get(siblings.size() - 1).getRank();
        return new Placement(last, null);
    }

    private int indexOf(List<Card> siblings, UUID id, String field) {
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(id)) {
                return i;
            }
        }
        throw new IllegalArgumentException(field + " is not a card in the target column");
    }

    /** Re-space the whole column: fresh short evenly-spread ranks, same order. */
    private void rebalance(List<Card> siblings) {
        List<String> fresh = LexoRank.spread(siblings.size());
        for (int i = 0; i < siblings.size(); i++) {
            siblings.get(i).rebalanceRank(fresh.get(i));
        }
        cards.saveAll(siblings);
    }

    /**
     * Load a card and assert the caller holds at least {@code required} on its board — 404 if
     * the card is gone or the board isn't theirs, 403 if their role is too weak.
     */
    private Card requireCardOnBoard(UUID cardId, UUID userId, Role required) {
        Card card = cards.findById(cardId)
                .orElseThrow(() -> new NotFoundException("Card not found"));
        boardService.requireBoardAccess(card.getBoardId(), userId, required);
        return card;
    }
}
