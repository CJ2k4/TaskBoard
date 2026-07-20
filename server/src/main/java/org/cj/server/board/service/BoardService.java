package org.cj.server.board.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.board.dto.BoardDetailResponse;
import org.cj.server.board.dto.BoardWithRole;
import org.cj.server.board.entity.Board;
import org.cj.server.board.entity.BoardColumn;
import org.cj.server.board.entity.BoardMembership;
import org.cj.server.board.entity.Card;
import org.cj.server.board.entity.MembershipStatus;
import org.cj.server.board.entity.Role;
import org.cj.server.board.repository.BoardColumnRepository;
import org.cj.server.board.repository.BoardMembershipRepository;
import org.cj.server.board.repository.BoardRepository;
import org.cj.server.board.repository.CardRepository;
import org.cj.server.common.exception.ForbiddenException;
import org.cj.server.common.exception.NotFoundException;

/**
 * Board business logic, HTTP-agnostic. Every operation is authorized through the shared
 * {@link #requireBoardAccess} guard — the single place in the app that answers "may this user
 * do this to this board?" — which the column, card, and membership services reuse.
 *
 * <p><b>M4 authorization is membership-based.</b> The caller's {@code board_membership} row
 * decides the outcome:
 *
 * <ul>
 *   <li><b>no ACTIVE membership</b> → 404, identical to a board that doesn't exist. We don't
 *       reveal that someone else's board is out there. (A PENDING invite grants nothing.)</li>
 *   <li><b>ACTIVE member, role too weak</b> → 403. Here the board's existence is already known
 *       to the caller, so the honest error is the useful one.</li>
 *   <li><b>ACTIVE member, role sufficient</b> → proceed.</li>
 * </ul>
 *
 * <p>Note that ownership is no longer read from {@code board.ownerId}: the owner's
 * OWNER/ACTIVE membership row — written by {@link #create} since M2 for exactly this reason —
 * is what proves it. One table answers every access question.
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
     * Create a board and, atomically, the owner's {@code OWNER}/{@code ACTIVE} membership row —
     * the row that makes the creator an owner as far as the guard is concerned.
     */
    @Transactional
    public Board create(String name, UUID ownerId) {
        Board board = boards.save(Board.create(name, ownerId));
        memberships.save(BoardMembership.createOwner(board.getId(), ownerId));
        return board;
    }

    /**
     * Every board the user can see — owned <em>or</em> shared with them — newest first, each
     * paired with the role they hold on it. Two queries regardless of board count: the
     * memberships, then the boards behind them (no per-board role lookup).
     */
    @Transactional(readOnly = true)
    public List<BoardWithRole> listAccessible(UUID userId) {
        List<BoardMembership> mine = memberships.findByUserIdAndStatus(userId, MembershipStatus.ACTIVE);
        if (mine.isEmpty()) {
            return List.of();
        }
        List<UUID> boardIds = mine.stream().map(BoardMembership::getBoardId).toList();
        return boards.findByIdInOrderByCreatedAtDesc(boardIds).stream()
                .map(board -> new BoardWithRole(board, roleOn(mine, board.getId())))
                .toList();
    }

    /** A single board the user is a member of, or 404. */
    @Transactional(readOnly = true)
    public Board get(UUID boardId, UUID userId) {
        return requireBoardAccess(boardId, userId, Role.VIEWER);
    }

    /**
     * The whole board — columns and cards, each in rank order — for rendering. All the board's
     * cards load in one query via the denormalized {@code card.board_id} (no join through
     * columns), then are grouped by column in memory.
     *
     * <p>The caller's role rides along in the response so the client knows whether to render
     * the board editable (it's already loaded here; asking for it again would be a wasted query).
     */
    @Transactional(readOnly = true)
    public BoardDetailResponse getDetail(UUID boardId, UUID userId) {
        BoardMembership membership = requireMembership(boardId, userId, Role.VIEWER);
        Board board = loadBoard(boardId);
        List<BoardColumn> boardColumns = columns.findByBoardIdOrderByRankAsc(boardId);
        List<Card> boardCards = cards.findByBoardIdOrderByRankAsc(boardId);
        return BoardDetailResponse.of(board, boardColumns, boardCards, membership.getRole());
    }

    /** Rename a board — owner only; an editor may change the contents, not the board itself. */
    @Transactional
    public Board rename(UUID boardId, UUID userId, String name) {
        Board board = requireBoardAccess(boardId, userId, Role.OWNER);
        board.rename(name);
        return boards.save(board);
    }

    /** Delete a board (owner only); the DB cascades to its memberships, columns, and cards. */
    @Transactional
    public void delete(UUID boardId, UUID userId) {
        Board board = requireBoardAccess(boardId, userId, Role.OWNER);
        boards.delete(board);
    }

    /**
     * The shared access guard: assert the user holds an ACTIVE membership on the board with at
     * least {@code required}'s capabilities, and return the board. Used by every board-scoped
     * operation, including those on columns, cards, and memberships.
     *
     * @throws NotFoundException  the board doesn't exist, or the user isn't an active member
     * @throws ForbiddenException the user is a member but their role is too weak
     */
    @Transactional(readOnly = true)
    public Board requireBoardAccess(UUID boardId, UUID userId, Role required) {
        requireMembership(boardId, userId, required);
        return loadBoard(boardId);
    }

    /**
     * The guard's core, returning the membership itself for callers that need the role. Kept
     * separate from {@link #requireBoardAccess} so {@code getDetail} can report the caller's
     * role without a second lookup.
     */
    BoardMembership requireMembership(UUID boardId, UUID userId, Role required) {
        // Check the board exists first, so a bad id reads as "not found" rather than
        // "you're not a member" — same 404 either way, but the message stays truthful.
        loadBoard(boardId);

        BoardMembership membership = memberships.findByBoardIdAndUserId(boardId, userId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("Board not found"));

        if (!membership.getRole().atLeast(required)) {
            throw new ForbiddenException("Requires " + required + " access on this board");
        }
        return membership;
    }

    private Board loadBoard(UUID boardId) {
        return boards.findById(boardId)
                .orElseThrow(() -> new NotFoundException("Board not found"));
    }

    /** The role from the already-loaded membership list — no extra query per board. */
    private Role roleOn(List<BoardMembership> mine, UUID boardId) {
        return mine.stream()
                .filter(m -> m.getBoardId().equals(boardId))
                .findFirst()
                .map(BoardMembership::getRole)
                .orElseThrow(() -> new IllegalStateException("No membership for board " + boardId));
    }
}
