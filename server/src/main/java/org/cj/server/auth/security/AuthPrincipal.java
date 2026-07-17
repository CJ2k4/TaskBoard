package org.cj.server.auth.security;

import java.util.UUID;

/**
 * The authenticated identity we attach to a request after verifying its access token.
 * Stored as the Spring Security "principal", so controllers can read it with
 * {@code @AuthenticationPrincipal AuthPrincipal me}. Intentionally tiny — just enough to
 * know who's calling; anything more (fresh name, avatar) is loaded from the DB when needed.
 */
public record AuthPrincipal(UUID userId, String email) {
}
