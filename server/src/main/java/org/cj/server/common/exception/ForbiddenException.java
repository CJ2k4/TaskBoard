package org.cj.server.common.exception;

/**
 * Thrown when an authenticated user is not allowed to perform an action — the global handler
 * maps it to 403. Mirror of {@link NotFoundException}/{@link ConflictException}, different
 * status.
 *
 * <p>Note the M2 access convention: for a board a user has <em>no</em> relationship to, we
 * throw {@link NotFoundException} (404) instead, so we don't reveal the board exists. 403 is
 * for "you're related to this but this specific action isn't permitted" — which becomes the
 * norm once per-role checks land in M4 (a VIEWER trying to edit).
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
