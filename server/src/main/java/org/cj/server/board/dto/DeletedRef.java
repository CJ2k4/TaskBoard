package org.cj.server.board.dto;

import java.util.UUID;

/**
 * The payload of a deletion event: the id of the thing that is now gone. There is nothing
 * else honest to send — the row no longer exists — and the id is all a client needs to drop
 * it from local state.
 */
public record DeletedRef(UUID id) {
}
