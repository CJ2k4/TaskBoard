package org.cj.server.board.dto;

import org.cj.server.board.entity.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/boards/{id}/invites}. The email need not belong to an existing
 * account — unknown emails become PENDING invites that activate when that email signs in.
 * {@code role} must be EDITOR or VIEWER; the service rejects OWNER (ownership transfer is
 * out of scope, and a board has exactly one owner).
 */
public record CreateInviteRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotNull Role role) {
}
