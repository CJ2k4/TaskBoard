package org.cj.server.board.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.board.dto.BoardDetailResponse;
import org.cj.server.board.entity.Board;
import org.cj.server.board.entity.BoardColumn;
import org.cj.server.board.entity.BoardMembership;
import org.cj.server.board.entity.Card;
import org.cj.server.board.repository.BoardColumnRepository;
import org.cj.server.board.repository.BoardMembershipRepository;
import org.cj.server.board.repository.BoardRepository;
import org.cj.server.board.repository.CardRepository;
import org.cj.server.common.exception.NotFoundException;

/**
 * Board business logic, HTTP-agnostic. Every operation is scoped to the current user; the
 * shared {@link #requireOwnedBoard} guard is the single place that answers "may this user touch
 * this board?" and is reused by the column and card services too.
 *
 * <p><b>M2 authorization</b> is owner-only: a board a user doesn't own is treated as
 * non-existent (404), so we never reveal it exists. When roles arrive in M4 this guard grows a
 * membership/role check (and starts distinguishing 403 for insufficient-role members).
 */
@Service
public class BoardService {

    private final BoardRepository boards;
    private final BoardMembershipRepository memberships;
    private final BoardColumnRepository columns;
    private final CardRepository cards;

    public BoardService(BoardRepository boards, BoardMembershipRepository memberships,
                        BoardColumnRepository columns, CardRepository cards) {
        this.boards = boards;
        this.memberships = memberships;
        this.columns = columns;
        this.cards = cards;
    }

    /**
     * Create a board and, atomically, the owner's {@code OWNER}/{@code ACTIVE} membership row.
     * Writing the membership now means M4 can switch to membership-based auth with no backfill.
     */
    @Transactional
    public Board create(String name, UUID ownerId) {
        Board board = boards.save(Board.create(name, ownerId));
        memberships.save(BoardMembership.createOwner(board.getId(), ownerId));
        return board;
    }

    /** Boards owned by the user, newest first. */
    @Transactional(readOnly = true)
    public List<Board> listOwned(UUID userId) {
        return boards.findByOwnerIdOrderByCreatedAtDesc(userId);
    }

    /** A single board the user owns, or 404. */
    @Transactional(readOnly = true)
    public Board get(UUID boardId, UUID userId) {
        return requireOwnedBoard(boardId, userId);
    }

    /**
     * The whole board — columns and cards, each in rank order — for rendering. All the board's
     * cards load in one query via the denormalized {@code card.board_id} (no join through
     * columns), then are grouped by column in memory.
     */
    @Transactional(readOnly = true)
    public BoardDetailResponse getDetail(UUID boardId, UUID userId) {
        Board board = requireOwnedBoard(boardId, userId);
        List<BoardColumn> boardColumns = columns.findByBoardIdOrderByRankAsc(boardId);
        List<Card> boardCards = cards.findByBoardIdOrderByRankAsc(boardId);
        return BoardDetailResponse.of(board, boardColumns, boardCards);
    }

    @Transactional
    public Board rename(UUID boardId, UUID userId, String name) {
        Board board = requireOwnedBoard(boardId, userId);
        board.rename(name);
        return boards.save(board);
    }

    /** Delete a board; the DB cascades to its memberships, columns, and cards. */
    @Transactional
    public void delete(UUID boardId, UUID userId) {
        Board board = requireOwnedBoard(boardId, userId);
        boards.delete(board);
    }

    /**
     * Load a board and assert the user may access it, else throw {@link NotFoundException}.
     * The shared guard for every board-scoped operation, including columns and cards. In M2
     * "may access" means "owns"; both missing and not-owned map to 404 (don't leak existence).
     */
    @Transactional(readOnly = true)
    public Board requireOwnedBoard(UUID boardId, UUID userId) {
        Board board = boards.findById(boardId)
                .orElseThrow(() -> new NotFoundException("Board not found"));
        if (!board.getOwnerId().equals(userId)) {
            throw new NotFoundException("Board not found");
        }
        return board;
    }
}
