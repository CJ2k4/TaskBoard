package org.cj.server.realtime.dto;

import java.util.UUID;

/**
 * One person currently viewing a board — the element type of a {@code PRESENCE} event's payload
 * (M6). Just enough to draw an avatar: the {@code userId} to dedupe and to let a client spot
 * itself, and the {@code name} to label the avatar and its tooltip.
 *
 * <p>Deliberately not a {@code MembershipResponse}: presence is "who has this board open right
 * now", which is a fact about live sockets, not about the roster — a member with the board closed
 * isn't here, and (in principle) the two lists answer different questions.
 */
public record PresenceViewer(UUID userId, String name) {
}
