package org.cj.server.board.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.auth.entity.User;
import org.cj.server.auth.repository.UserRepository;
import org.cj.server.board.dto.ActivityResponse;
import org.cj.server.board.entity.BoardActivity;
import org.cj.server.board.entity.Role;
import org.cj.server.board.repository.BoardActivityRepository;

/**
 * Reads the activity log (M6). Writing is {@link ActivityRecorder}'s job, off domain events; this
 * side only ever reads, newest-first, page-limited, for any member of the board (VIEWER+).
 *
 * <p>Access goes through the same {@link BoardService#requireBoardAccess} guard as everything else,
 * so a non-member gets the identical 404 they'd get asking for the board itself — the log doesn't
 * leak that a board exists, nor what happens on it.
 */
@Service
public class ActivityService {

    /** Sane page sizes: a default that fills a panel, a ceiling so a client can't ask for it all. */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final BoardActivityRepository activities;
    private final UserRepository users;
    private final BoardService boardService;

    public ActivityService(BoardActivityRepository activities, UserRepository users,
                           BoardService boardService) {
        this.activities = activities;
        this.users = users;
        this.boardService = boardService;
    }

    /**
     * A board's activity, newest first. {@code before} (a cursor timestamp) pages backward through
     * history for "load more"; null means start from the top. {@code limit} is clamped to
     * {@code [1, MAX_LIMIT]}.
     */
    @Transactional(readOnly = true)
    public List<ActivityResponse> list(UUID boardId, UUID callerId, Integer limit, Instant before) {
        boardService.requireBoardAccess(boardId, callerId, Role.VIEWER);

        Pageable page = PageRequest.of(0, clampLimit(limit));
        List<BoardActivity> rows = before == null
                ? activities.findByBoardIdOrderByCreatedAtDesc(boardId, page)
                : activities.findByBoardIdAndCreatedAtBeforeOrderByCreatedAtDesc(boardId, before, page);

        // Batch-load the actors behind these rows (one query, not one per entry) — same shape as
        // MembershipService.listMembers.
        List<UUID> actorIds = rows.stream()
                .map(BoardActivity::getActorId).filter(Objects::nonNull).toList();
        Map<UUID, User> byId = users.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return rows.stream()
                .map(a -> ActivityResponse.from(a, a.getActorId() != null ? byId.get(a.getActorId()) : null))
                .toList();
    }

    private int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
