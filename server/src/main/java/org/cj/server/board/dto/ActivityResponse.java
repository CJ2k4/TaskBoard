package org.cj.server.board.dto;

import java.time.Instant;
import java.util.UUID;

import org.cj.server.auth.entity.User;
import org.cj.server.board.entity.BoardActivity;

/**
 * One entry in the activity feed. {@code summary} is the stored predicate ("moved card …") and
 * {@code actorName} is joined from {@code app_user} at read time, so the client renders
 * "{actorName} {summary}". {@code actorName} is null when the actor's account has since been
 * deleted — the client shows "Someone" in that case.
 */
public record ActivityResponse(
        UUID id,
        UUID actorId,
        String actorName,
        BoardEventType type,
        String summary,
        Instant createdAt) {

    /** {@code actor} may be null (deleted account, or an actorless entry). */
    public static ActivityResponse from(BoardActivity activity, User actor) {
        return new ActivityResponse(
                activity.getId(),
                activity.getActorId(),
                actor != null ? actor.getName() : null,
                activity.getType(),
                activity.getSummary(),
                activity.getCreatedAt());
    }
}
