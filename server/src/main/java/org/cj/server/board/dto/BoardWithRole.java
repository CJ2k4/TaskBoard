package org.cj.server.board.dto;

import org.cj.server.board.entity.Board;
import org.cj.server.board.entity.Role;

/**
 * A board together with the current caller's role on it — what the board-list query naturally
 * produces, since it walks the caller's memberships to find the boards in the first place.
 *
 * <p>An internal pairing rather than a wire type: the controller turns it into
 * {@link BoardResponse}. It exists so the service can hand back both halves without the
 * controller re-querying the role per board.
 */
public record BoardWithRole(Board board, Role role) {
}
