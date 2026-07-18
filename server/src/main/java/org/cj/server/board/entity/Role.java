package org.cj.server.board.entity;

/**
 * A member's role on a board. Stored as a string (never an ordinal) and guarded by a DB
 * CHECK constraint. M2 only creates {@link #OWNER}; {@link #EDITOR}/{@link #VIEWER} are used
 * once sharing + role enforcement land in M4.
 */
public enum Role {
    OWNER,
    EDITOR,
    VIEWER
}
