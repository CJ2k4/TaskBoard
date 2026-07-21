package org.cj.server.board.dto;

import java.util.UUID;

import org.cj.server.board.entity.Role;

/**
 * The outcome of redeeming an invite link (M6): which board the caller now has access to, and at
 * what role. Enough for the client to navigate straight to {@code /boards/{boardId}}. Returned for
 * a fresh join and for a no-op re-redeem alike — the caller doesn't need to know which it was.
 */
public record JoinResult(UUID boardId, Role role) {
}
