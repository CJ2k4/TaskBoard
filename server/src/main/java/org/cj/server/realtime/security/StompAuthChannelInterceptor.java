package org.cj.server.realtime.security;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import org.cj.server.auth.security.AuthPrincipal;
import org.cj.server.auth.service.JwtService;
import org.cj.server.board.entity.Role;
import org.cj.server.board.service.BoardService;
import org.cj.server.realtime.BoardTopic;
import org.cj.server.common.exception.ForbiddenException;
import org.cj.server.common.exception.NotFoundException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * The security gate on the WebSocket, doing for STOMP frames what
 * {@link org.cj.server.auth.security.JwtAuthenticationFilter} does for HTTP requests. It runs
 * on the inbound channel, so every frame a client sends passes through it first.
 *
 * <p>Two frames matter:
 *
 * <ul>
 *   <li><b>CONNECT</b> — carries {@code Authorization: Bearer <accessToken>} as a STOMP
 *       <em>native header</em>. (It can't ride on the HTTP upgrade: the browser WebSocket API
 *       gives you no way to set request headers. So the upgrade itself is public — see the
 *       {@code /ws/**} rule in {@code SecurityConfig} — and identity is proved one frame
 *       later.) The verified principal is attached to the STOMP session, and Spring re-attaches
 *       it to every later frame from that session.</li>
 *   <li><b>SUBSCRIBE</b> — the real authorization decision. A board topic may only be joined by
 *       an ACTIVE member of that board, checked through the very same
 *       {@link BoardService#requireBoardAccess} guard the REST endpoints use. Without this a
 *       logged-in stranger could subscribe to any board id and watch it live: the socket would
 *       be a hole straight through the permission model the REST layer so carefully
 *       enforces.</li>
 * </ul>
 *
 * <p><b>Failure is refusal, not degradation.</b> Unlike the HTTP filter — which leaves a bad
 * token anonymous and lets Spring Security answer 401 later — there is no "later" here: a
 * subscription that is allowed to proceed starts streaming. So anything unexpected throws, and
 * Spring answers with an ERROR frame and closes the session.
 *
 * <p>Note the deliberate vagueness of the SUBSCRIBE error. The guard distinguishes 404 (not a
 * member) from 403 (member, wrong role), but both collapse to one message here — the REST API
 * can afford to be precise because it answers a specific question about a specific board; on
 * the socket the distinction would just hand out a board-existence oracle.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final BoardService boardService;

    public StompAuthChannelInterceptor(JwtService jwt, BoardService boardService) {
        this.jwt = jwt;
        this.boardService = boardService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // getAccessor (not StompHeaderAccessor.wrap) returns the *mutable* accessor for this
        // message — a wrapped copy would happily accept setUser and then throw the result away.
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            accessor.setUser(authenticate(accessor));
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscription(accessor);
        }
        return message;
    }

    /** Verify the CONNECT frame's access token and turn it into the session's principal. */
    private Authentication authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER)) {
            throw new StompSecurityException("Missing access token");
        }
        String token = header.substring(BEARER.length()).trim();

        Claims claims;
        try {
            claims = jwt.parse(token); // verifies signature + expiry
        } catch (JwtException | IllegalArgumentException ex) {
            // Never echo the parse failure back — "expired" vs "bad signature" is free
            // intelligence for anyone probing.
            throw new StompSecurityException("Invalid access token");
        }
        // A refresh token buys new access tokens and nothing else — least of all a live feed.
        if (jwt.isRefresh(claims)) {
            throw new StompSecurityException("Invalid access token");
        }

        AuthPrincipal principal = new AuthPrincipal(jwt.userId(claims), claims.get("email", String.class));
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    /** Allow the subscription only if the caller is an active member of that board. */
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        UUID boardId = boardIdIn(destination);
        AuthPrincipal me = currentUser(accessor);

        try {
            boardService.requireBoardAccess(boardId, me.userId(), Role.VIEWER);
        } catch (NotFoundException | ForbiddenException ex) {
            throw new StompSecurityException("Not allowed to subscribe to " + destination);
        }
    }

    private UUID boardIdIn(String destination) {
        if (destination == null) {
            throw new StompSecurityException("Missing destination");
        }
        return BoardTopic.boardId(destination)
                .orElseThrow(() -> new StompSecurityException("Unknown destination " + destination));
    }

    /**
     * The principal CONNECT established for this session. Absent means the frame arrived on a
     * session that never authenticated — refuse rather than assume.
     */
    private AuthPrincipal currentUser(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof Authentication auth && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal;
        }
        throw new StompSecurityException("Not authenticated");
    }
}
