package org.cj.server.board.entity;

/**
 * A member's role on a board, declared strongest-first. Stored as a string (never an
 * ordinal) and guarded by a DB CHECK constraint.
 *
 * <p>Capabilities are cumulative: OWNER can do everything EDITOR can, EDITOR everything
 * VIEWER can. {@link #atLeast} encodes that ladder for the authorization guard.
 */
public enum Role {
    OWNER,
    EDITOR,
    VIEWER;

    /**
     * True if this role grants at least {@code required}'s capabilities. Uses declaration
     * order (OWNER &lt; EDITOR &lt; VIEWER by ordinal), which is safe because storage is
     * {@code @Enumerated(STRING)} — the ordinal never touches the database.
     */
    public boolean atLeast(Role required) {
        return this.ordinal() <= required.ordinal();
    }
}
