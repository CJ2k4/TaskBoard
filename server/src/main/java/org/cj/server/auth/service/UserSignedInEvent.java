package org.cj.server.auth.service;

import java.util.UUID;

/**
 * Published by {@link AuthService} whenever a user successfully registers or logs in.
 *
 * <p>Why an event instead of a direct call? Other features want to react to sign-in — the
 * board feature resolves pending invites (M4.2) — but {@code auth} must not import board
 * services: the board package already depends on auth, and a call back the other way would
 * create a package cycle. An application event inverts the dependency: auth just announces
 * "this user signed in" and doesn't know or care who listens.
 */
public record UserSignedInEvent(UUID userId, String email) {
}
