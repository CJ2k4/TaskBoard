package org.cj.server.board.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.cj.server.board.entity.Role;

/**
 * A board as it appears on the dashboard overview grid: its own fields plus the summary counts
 * ({@code columnCount}/{@code cardCount}) and the active member roster the card renders as an
 * avatar stack. {@code myRole} is the caller's role (like {@link BoardResponse}); the members
 * carry only what an avatar needs ({@code userId} for a stable colour, {@code name} for the
 * monogram) — never emails or roles, which the dashboard has no business showing.
 *
 * <p>Distinct from {@link BoardResponse} on purpose: the counts and roster are list-only
 * enrichment the service computes, so create/rename keep returning the plain {@code BoardResponse}
 * rather than paying for aggregation they don't need.
 */
public record BoardOverviewResponse(
        UUID id,
        String name,
        String description,
        UUID ownerId,
        Role myRole,
        long columnCount,
        long cardCount,
        List<MemberSummary> members,
        Instant createdAt,
        Instant updatedAt) {

    /** The bare minimum an avatar needs: a stable id (for colour) and a display name. */
    public record MemberSummary(UUID userId, String name) {
    }
}
