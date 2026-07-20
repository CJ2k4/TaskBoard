package org.cj.server.board.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.board.dto.MoveColumnRequest;
import org.cj.server.board.entity.BoardColumn;
import org.cj.server.board.entity.Role;
import org.cj.server.board.repository.BoardColumnRepository;
import org.cj.server.board.repository.CardRepository;
import org.cj.server.common.exception.ConflictException;
import org.cj.server.common.exception.NotFoundException;
import org.cj.server.common.ranking.LexoRank;
import org.cj.server.common.ranking.RankExhaustedException;

/**
 * Column business logic. Every operation is authorized by delegating to
 * {@link BoardService#requireBoardAccess} — a column is only reachable through a board the user
 * is a member of, so we never repeat access logic here. Columns are board <em>content</em>, so
 * every mutation requires {@link Role#EDITOR}; viewers get a 403.
 *
 * <p>New columns are <b>appended</b>: the server computes a rank after the current last column
 * with {@link LexoRank}. Clients never supply a rank (moves/reordering are M3).
 */
@Service
public class ColumnService {

    private final BoardColumnRepository columns;
    private final CardRepository cards;
    private final BoardService boardService;

    public ColumnService(BoardColumnRepository columns, CardRepository cards, BoardService boardService) {
        this.columns = columns;
        this.cards = cards;
        this.boardService = boardService;
    }

    /** Append a column to the end of a board the caller can edit. */
    @Transactional
    public BoardColumn create(UUID boardId, UUID userId, String title) {
        boardService.requireBoardAccess(boardId, userId, Role.EDITOR);
        String lastRank = columns.findFirstByBoardIdOrderByRankDesc(boardId)
                .map(BoardColumn::getRank)
                .orElse(null);
        String rank = LexoRank.between(lastRank, null);
        return columns.save(BoardColumn.create(boardId, title, rank));
    }

    @Transactional
    public BoardColumn rename(UUID columnId, UUID userId, String title) {
        BoardColumn column = requireColumnOnBoard(columnId, userId, Role.EDITOR);
        column.rename(title);
        return columns.save(column);
    }

    /** Delete a column, but only if it's empty — force the user to move/clear cards first. */
    @Transactional
    public void delete(UUID columnId, UUID userId) {
        BoardColumn column = requireColumnOnBoard(columnId, userId, Role.EDITOR);
        if (cards.existsByColumnId(columnId)) {
            throw new ConflictException("Column is not empty; move or delete its cards first");
        }
        columns.delete(column);
    }

    /**
     * Reorder a column among its board's columns — the M3 column-drag write. Same
     * intent-resolution and re-balance-retry shape as {@code CardService.move}, one level up;
     * see {@link MoveColumnRequest} for the semantics. Columns never change boards.
     */
    @Transactional
    public BoardColumn move(UUID columnId, UUID userId, MoveColumnRequest req) {
        BoardColumn column = requireColumnOnBoard(columnId, userId, Role.EDITOR);

        // Future siblings in current rank order, minus the moving column itself.
        List<BoardColumn> siblings = columns.findByBoardIdOrderByRankAsc(column.getBoardId()).stream()
                .filter(c -> !c.getId().equals(column.getId()))
                .toList();

        Placement placement = resolvePlacement(siblings, req.afterColumnId(), req.beforeColumnId());
        String rank;
        try {
            rank = LexoRank.between(placement.prev(), placement.next());
        } catch (RankExhaustedException ex) {
            rebalance(siblings);
            placement = resolvePlacement(siblings, req.afterColumnId(), req.beforeColumnId());
            rank = LexoRank.between(placement.prev(), placement.next());
        }

        column.moveTo(rank);
        return columns.save(column);
    }

    /** The rank bounds a move must land between; either side may be null (open end). */
    private record Placement(String prev, String next) {}

    /** Same anchor semantics as the card version: after wins, then before, else append. */
    private Placement resolvePlacement(List<BoardColumn> siblings, UUID afterId, UUID beforeId) {
        if (afterId != null) {
            int i = indexOf(siblings, afterId, "afterColumnId");
            String next = i + 1 < siblings.size() ? siblings.get(i + 1).getRank() : null;
            return new Placement(siblings.get(i).getRank(), next);
        }
        if (beforeId != null) {
            int i = indexOf(siblings, beforeId, "beforeColumnId");
            String prev = i > 0 ? siblings.get(i - 1).getRank() : null;
            return new Placement(prev, siblings.get(i).getRank());
        }
        String last = siblings.isEmpty() ? null : siblings.get(siblings.size() - 1).getRank();
        return new Placement(last, null);
    }

    private int indexOf(List<BoardColumn> siblings, UUID id, String field) {
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(id)) {
                return i;
            }
        }
        throw new IllegalArgumentException(field + " is not a column on this board");
    }

    /** Re-space the whole board's columns: fresh short evenly-spread ranks, same order. */
    private void rebalance(List<BoardColumn> siblings) {
        List<String> fresh = LexoRank.spread(siblings.size());
        for (int i = 0; i < siblings.size(); i++) {
            siblings.get(i).rebalanceRank(fresh.get(i));
        }
        columns.saveAll(siblings);
    }

    /**
     * Load a column and assert the caller holds at least {@code required} on its board — 404 if
     * the column is gone or the board isn't theirs, 403 if their role is too weak.
     */
    private BoardColumn requireColumnOnBoard(UUID columnId, UUID userId, Role required) {
        BoardColumn column = columns.findById(columnId)
                .orElseThrow(() -> new NotFoundException("Column not found"));
        boardService.requireBoardAccess(column.getBoardId(), userId, required);
        return column;
    }
}
