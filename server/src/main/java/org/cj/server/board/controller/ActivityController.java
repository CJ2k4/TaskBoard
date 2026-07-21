package org.cj.server.board.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.cj.server.auth.security.AuthPrincipal;
import org.cj.server.board.dto.ActivityResponse;
import org.cj.server.board.service.ActivityService;

/**
 * The activity feed endpoint (M6): {@code GET /api/boards/{boardId}/activity}, newest first,
 * readable by any member. {@code limit} caps the page; {@code before} (an ISO-8601 instant) pages
 * backward through history for "load more".
 */
@RestController
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/api/boards/{boardId}/activity")
    public List<ActivityResponse> activity(
            @PathVariable UUID boardId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @AuthenticationPrincipal AuthPrincipal me) {
        return activityService.list(boardId, me.userId(), limit, before);
    }
}
