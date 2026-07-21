package org.cj.server.board.dto;

import java.util.UUID;

import org.cj.server.board.entity.Board;
import org.cj.server.board.entity.Role;

/**
 * A board's current shareable invite link (M6). Both fields are null when no link is active —
 * {@code GET} answers 200 with a null token rather than a 404 so the owner's UI can tell "no link
 * yet" apart from a real error without catching an exception. The client builds the actual URL
 * ({@code /join/{token}}); the server only owns the token and the role it grants.
 */
public record InviteLinkResponse(UUID token, Role role) {

    public static InviteLinkResponse from(Board board) {
        return new InviteLinkResponse(board.getInviteToken(), board.getInviteLinkRole());
    }
}
