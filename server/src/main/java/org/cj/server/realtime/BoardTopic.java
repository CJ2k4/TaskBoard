package org.cj.server.realtime;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that knows the shape of a board's STOMP destination — {@code /topic/board/{id}}.
 *
 * <p>Three parties need it and must agree exactly: {@code StompAuthChannelInterceptor} (which
 * board may this SUBSCRIBE join?), {@code BoardEventBroadcaster} (where does a change go?), and
 * {@code PresenceTracker} (which board did this session open?). A drift between them would be a
 * security-relevant bug — the guard authorizing one destination while events fan out on another —
 * so the pattern and the prefix live here, once.
 */
public final class BoardTopic {

    /** The destination prefix; the {@code {boardId}} suffix is what makes fan-out per-board. */
    public static final String PREFIX = "/topic/board/";

    /** The only destination shape this app publishes to; anything else is not a board topic. */
    private static final Pattern PATTERN = Pattern.compile("^/topic/board/([0-9a-fA-F-]{36})$");

    private BoardTopic() {
    }

    /** The destination for a board's topic. */
    public static String of(UUID boardId) {
        return PREFIX + boardId;
    }

    /** The board id embedded in a destination, or empty if it isn't a well-formed board topic. */
    public static Optional<UUID> boardId(String destination) {
        if (destination == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(destination);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(matcher.group(1)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
