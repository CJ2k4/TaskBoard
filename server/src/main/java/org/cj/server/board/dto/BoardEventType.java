package org.cj.server.board.dto;

/**
 * What happened to a board — the discriminator on every real-time message a client receives
 * (M5). Named after the change, not the endpoint, because a client cares that a card moved,
 * not that someone called {@code PATCH /api/cards/{id}/move}.
 *
 * <p>Each constant fixes the shape of the event's payload, and the client switches on it:
 *
 * <ul>
 *   <li>{@code CARD_*} → {@link CardResponse}, except {@code CARD_DELETED} → {@link DeletedRef}</li>
 *   <li>{@code COLUMN_*} → {@link ColumnResponse}, except {@code COLUMN_DELETED} → {@link DeletedRef}</li>
 *   <li>{@code BOARD_UPDATED} → {@link BoardSummary}, {@code BOARD_DELETED} → {@link DeletedRef}</li>
 *   <li>{@code MEMBER_*} → {@link MembershipResponse} (removal included: clients need the
 *       {@code userId} to tell whether the person shown the door was <em>them</em>)</li>
 * </ul>
 *
 * <p>It lives in {@code dto} rather than beside the event class in {@code service} so the
 * real-time wire DTO can reference it without a dto→service import.
 */
public enum BoardEventType {
    CARD_CREATED,
    CARD_UPDATED,
    CARD_MOVED,
    CARD_DELETED,

    COLUMN_CREATED,
    COLUMN_UPDATED,
    COLUMN_MOVED,
    COLUMN_DELETED,

    BOARD_UPDATED,
    BOARD_DELETED,

    MEMBER_ADDED,
    MEMBER_UPDATED,
    MEMBER_REMOVED
}
