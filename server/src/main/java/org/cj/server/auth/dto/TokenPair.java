package org.cj.server.auth.dto;

/**
 * A freshly minted access + refresh token pair, returned by the JWT service on
 * register/login/refresh.
 *
 * <p>Why this lives in {@code dto} rather than nested inside {@code JwtService}: it used to
 * be {@code JwtService.Tokens}, which forced {@link AuthResponse} — a DTO — to import a
 * <em>service</em> to describe its own shape. That's the dependency arrow pointing the wrong
 * way: DTOs are the vocabulary the layers exchange, so they must not depend on the layers
 * that produce them. Pulling the record out here lets the service return it and the response
 * consume it, with neither knowing about the other.
 */
public record TokenPair(String accessToken, String refreshToken) {
}
