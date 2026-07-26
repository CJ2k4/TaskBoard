package org.cj.server.board.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.auth.entity.User;
import org.cj.server.auth.repository.UserRepository;
import org.cj.server.board.dto.BoardDetailResponse;
import org.cj.server.board.dto.BoardEventType;
import org.cj.server.board.dto.BoardOverviewResponse;
import org.cj.server.board.dto.BoardSummary;
import org.cj.server.board.dto.DeletedRef;
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
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    public BoardService(BoardRepository boards, BoardMembershipRepository memberships,
                        BoardColumnRepository columns, CardRepository cards,
                        UserRepository users, ApplicationEventPublisher events) {
        this.boards = boards;
        this.memberships = memberships;
        this.columns = columns;
        this.cards = cards;
        this.users = users;
        this.events = events;
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
     * The dashboard overview: every board the user can see — owned <em>or</em> shared — newest
     * first, each with the caller's role, its column/card counts, and its active member roster
     * (for the avatar stack). Batched to stay roughly constant in query count regardless of how
     * many boards: the caller's memberships, the boards, all rosters in one {@code IN} query, and
     * the users behind them in one more — then a per-board count pair (cheap at this scale).
     */
    @Transactional(readOnly = true)
    public List<BoardOverviewResponse> listOverview(UUID userId) {
        List<BoardMembership> mine = memberships.findByUserIdAndStatus(userId, MembershipStatus.ACTIVE);
        if (mine.isEmpty()) {
            return List.of();
        }
        List<UUID> boardIds = mine.stream().map(BoardMembership::getBoardId).toList();

        // All active members across these boards, grouped by board; then the users behind them.
        List<BoardMembership> allMembers =
                memberships.findByBoardIdInAndStatus(boardIds, MembershipStatus.ACTIVE);
        Map<UUID, User> usersById = users.findAllById(
                        allMembers.stream().map(BoardMembership::getUserId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, List<BoardMembership>> membersByBoard = allMembers.stream()
                .collect(Collectors.groupingBy(BoardMembership::getBoardId));

        return boards.findByIdInOrderByCreatedAtDesc(boardIds).stream()
                .map(board -> {
                    List<BoardOverviewResponse.MemberSummary> roster =
                            membersByBoard.getOrDefault(board.getId(), List.of()).stream()
                                    .map(m -> usersById.get(m.getUserId()))
                                    .filter(Objects::nonNull)
                                    .map(u -> new BoardOverviewResponse.MemberSummary(u.getId(), u.getName()))
                                    .toList();
                    return new BoardOverviewResponse(
                            board.getId(),
                            board.getName(),
                            board.getDescription(),
                            board.getOwnerId(),
                            roleOn(mine, board.getId()),
                            columns.countByBoardId(board.getId()),
                            cards.countByBoardIdAndDeletedAtIsNull(board.getId()),
                            roster,
                            board.getCreatedAt(),
                            board.getUpdatedAt());
                })
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
        List<Card> boardCards = cards.findByBoardIdAndDeletedAtIsNullOrderByRankAsc(boardId);
        return BoardDetailResponse.of(board, boardColumns, boardCards, membership.getRole());
    }

    /**
     * Edit a board's name and description — owner only; an editor may change the contents, not
     * the board itself. Both fields are sent together (the PATCH is a full edit of the board's
     * own fields), so a null description clears it.
     */
    @Transactional
    public Board update(UUID boardId, UUID userId, String name, String description) {
        Board board = requireBoardAccess(boardId, userId, Role.OWNER);
        board.edit(name, description);
        Board saved = boards.save(board);
        events.publishEvent(new BoardChangedEvent(
                boardId, userId, BoardEventType.BOARD_UPDATED, BoardSummary.from(saved)));
        return saved;
    }

    /**
     * Delete a board (owner only); the DB cascades to its memberships, columns, and cards.
     *
     * <p>The BOARD_DELETED broadcast is the last thing anyone hears on this topic — it exists so
     * members sitting on the board get sent home rather than staring at a board that no longer
     * exists and discovering it on their next 404.
     */
    @Transactional
    public void delete(UUID boardId, UUID userId) {
        Board board = requireBoardAccess(boardId, userId, Role.OWNER);
        boards.delete(board);
        events.publishEvent(new BoardChangedEvent(
                boardId, userId, BoardEventType.BOARD_DELETED, new DeletedRef(boardId)));
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
