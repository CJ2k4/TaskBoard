package org.cj.server.board.dto;

import org.cj.server.board.entity.Role;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/boards/{id}/invite-link} — the role a redeemer gets. Must be EDITOR or
 * VIEWER; the service rejects OWNER (a board has exactly one owner, and a public link must never
 * be able to mint another).
 */
public record CreateInviteLinkRequest(@NotNull Role role) {
}
