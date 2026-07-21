package org.cj.server.realtime.presence;

import java.security.Principal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import org.cj.server.auth.entity.User;
import org.cj.server.auth.repository.UserRepository;
import org.cj.server.auth.security.AuthPrincipal;
import org.cj.server.board.dto.BoardEventType;
import org.cj.server.realtime.BoardTopic;
import org.cj.server.realtime.dto.BoardEvent;
import org.cj.server.realtime.dto.PresenceViewer;

/**
 * "Who is looking at this board right now?" (M6) — derived entirely from the WebSocket's own
 * lifecycle, never from anything the client sends. The socket stays a one-way feed: the browser
 * subscribes to a board and, from that alone, Spring publishes {@link SessionSubscribeEvent} /
 * {@link SessionUnsubscribeEvent} / {@link SessionDisconnectEvent}, which is all this needs.
 *
 * <p>The identity on each event is the {@link AuthPrincipal} that
 * {@code StompAuthChannelInterceptor} attached at CONNECT and Spring re-attaches to every later
 * frame — so presence inherits the same authenticated identity the SUBSCRIBE guard already
 * checked. (A subscribe the guard refused never produces a {@code SessionSubscribeEvent}, so an
 * unauthorized viewer can't appear here either.)
 *
 * <p>Bookkeeping:
 * <ul>
 *   <li>A board's viewers are counted, not just flagged — one user with two tabs is present once,
 *       and closing one tab must not evict them. So each board holds {@code userId → open-tab
 *       count}, and a user drops out of the set only when their last tab goes.</li>
 *   <li>A disconnect gives us a session id and nothing else — no destination, no board. So every
 *       subscription is filed under {@code sessionId + subscriptionId}, letting an unsubscribe
 *       undo exactly one, and a disconnect undo all of that session's at once.</li>
 * </ul>
 *
 * <p>Any real change to a board's viewer set re-broadcasts the whole list on that board's topic
 * (the same {@code /topic/board/{id}} everything else uses), as a {@code PRESENCE}
 * {@link BoardEvent}. Sending the full list rather than a delta means a client that just joined
 * gets the complete picture from the broadcast its own subscribe triggered — no separate "current
 * state" request. This is not persisted and not transactional; it's live state about live sockets.
 */
@Component
public class PresenceTracker {

    private final SimpMessagingTemplate messaging;
    private final UserRepository users;

    /** boardId → (userId → number of that user's open tabs on this board). Guarded by itself. */
    private final Map<UUID, Map<UUID, Integer>> viewersByBoard = new HashMap<>();

    /** subscriptionKey → what it was viewing, so unsubscribe/disconnect can undo it precisely. */
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();

    private record Sub(UUID boardId, UUID userId) {}

    public PresenceTracker(SimpMessagingTemplate messaging, UserRepository users) {
        this.messaging = messaging;
        this.users = users;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        UUID boardId = BoardTopic.boardId(accessor.getDestination()).orElse(null);
        UUID userId = userId(accessor);
        if (boardId == null || userId == null) {
            return; // not a board topic, or an unauthenticated frame — nothing to track
        }
        // putIfAbsent guards against a duplicate SUBSCRIBE with the same id double-counting a tab.
        if (subs.putIfAbsent(key(accessor), new Sub(boardId, userId)) != null) {
            return;
        }
        increment(boardId, userId);
        // Always broadcast on subscribe — even a same-user second tab needs the current list
        // delivered to it, and it can only receive that off a broadcast to the topic.
        broadcast(boardId);
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        forget(key(StompHeaderAccessor.wrap(event.getMessage())));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String prefix = event.getSessionId() + KEY_SEP;
        // A disconnect tears down every subscription the session held.
        subs.keySet().stream().filter(k -> k.startsWith(prefix)).toList().forEach(this::forget);
    }

    /** Undo one recorded subscription; re-broadcast only if it actually removed a viewer. */
    private void forget(String key) {
        Sub sub = subs.remove(key);
        if (sub == null) {
            return;
        }
        if (decrement(sub.boardId(), sub.userId())) {
            broadcast(sub.boardId());
        }
    }

    private void increment(UUID boardId, UUID userId) {
        synchronized (viewersByBoard) {
            viewersByBoard.computeIfAbsent(boardId, b -> new HashMap<>()).merge(userId, 1, Integer::sum);
        }
    }

    /** @return true if this drop removed the user from the board's viewer set (last tab closed). */
    private boolean decrement(UUID boardId, UUID userId) {
        synchronized (viewersByBoard) {
            Map<UUID, Integer> board = viewersByBoard.get(boardId);
            if (board == null) {
                return false;
            }
            Integer count = board.get(userId);
            if (count == null) {
                return false;
            }
            boolean userLeft = count <= 1;
            if (userLeft) {
                board.remove(userId);
            } else {
                board.put(userId, count - 1);
            }
            if (board.isEmpty()) {
                viewersByBoard.remove(boardId);
            }
            return userLeft;
        }
    }

    /** Send the board's current viewer list to its topic, names resolved in one query. */
    private void broadcast(UUID boardId) {
        List<UUID> ids;
        synchronized (viewersByBoard) {
            Map<UUID, Integer> board = viewersByBoard.get(boardId);
            ids = board == null ? List.of() : List.copyOf(board.keySet());
        }
        Map<UUID, String> names = users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        List<PresenceViewer> viewers = ids.stream()
                .map(id -> new PresenceViewer(id, names.getOrDefault(id, "Someone")))
                .sorted(Comparator.comparing(PresenceViewer::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        // actorId is null: presence must reach everyone, so no client filters it as its own echo.
        messaging.convertAndSend(
                BoardTopic.of(boardId),
                new BoardEvent(BoardEventType.PRESENCE, boardId, null, Instant.now(), viewers));
    }

    private static final char KEY_SEP = '\0';

    private static String key(StompHeaderAccessor accessor) {
        return accessor.getSessionId() + KEY_SEP + accessor.getSubscriptionId();
    }

    private static UUID userId(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof Authentication auth && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.userId();
        }
        return null;
    }
}
