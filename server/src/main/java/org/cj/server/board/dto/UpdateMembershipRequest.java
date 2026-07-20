package org.cj.server.board.dto;

import org.cj.server.board.entity.Role;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/memberships/{id}} — change a member's role (EDITOR ↔ VIEWER).
 * OWNER is rejected by the service, and the owner's own row can never be re-roled.
 */
public record UpdateMembershipRequest(@NotNull Role role) {
}
