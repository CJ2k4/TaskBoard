package org.cj.server.board.entity;

/**
 * Whether a membership is live ({@link #ACTIVE}) or an unaccepted email invite
 * ({@link #PENDING}, resolved on the invitee's next sign-in — M4). Stored as a string with a
 * DB CHECK constraint. M2 only ever creates {@link #ACTIVE} owner rows.
 */
public enum MembershipStatus {
    ACTIVE,
    PENDING
}
