package org.cj.server.board.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.board.entity.BoardColumn;
import org.cj.server.board.repository.BoardColumnRepository;
import org.cj.server.board.repository.CardRepository;
import org.cj.server.common.exception.ConflictException;
import org.cj.server.common.exception.NotFoundException;
import org.cj.server.common.ranking.LexoRank;

/**
 * Column business logic. Every operation is authorized by delegating to
 * {@link BoardService#requireOwnedBoard} — a column is only reachable through a board the user
 * owns, so we never repeat access logic here.
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

    /** Append a column to the end of an owned board. */
    @Transactional
    public BoardColumn create(UUID boardId, UUID userId, String title) {
        boardService.requireOwnedBoard(boardId, userId);
        String lastRank = columns.findFirstByBoardIdOrderByRankDesc(boardId)
                .map(BoardColumn::getRank)
                .orElse(null);
        String rank = LexoRank.between(lastRank, null);
        return columns.save(BoardColumn.create(boardId, title, rank));
    }

    @Transactional
    public BoardColumn rename(UUID columnId, UUID userId, String title) {
        BoardColumn column = requireColumnOnOwnedBoard(columnId, userId);
        column.rename(title);
        return columns.save(column);
    }

    /** Delete a column, but only if it's empty — force the user to move/clear cards first. */
    @Transactional
    public void delete(UUID columnId, UUID userId) {
        BoardColumn column = requireColumnOnOwnedBoard(columnId, userId);
        if (cards.existsByColumnId(columnId)) {
            throw new ConflictException("Column is not empty; move or delete its cards first");
        }
        columns.delete(column);
    }

    /** Load a column and assert the caller owns its board, else 404. */
    private BoardColumn requireColumnOnOwnedBoard(UUID columnId, UUID userId) {
        BoardColumn column = columns.findById(columnId)
                .orElseThrow(() -> new NotFoundException("Column not found"));
        boardService.requireOwnedBoard(column.getBoardId(), userId);
        return column;
    }
}
