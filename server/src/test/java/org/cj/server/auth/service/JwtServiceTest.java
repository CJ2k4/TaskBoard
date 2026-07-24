package org.cj.server.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.cj.server.auth.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;

/**
 * Unit tests for {@link JwtService}. No Spring context — we construct the service directly
 * with a test secret and explicit token lifetimes, which also lets us mint an already-expired
 * token deterministically (negative TTL) instead of sleeping.
 */
class JwtServiceTest {

    // Must be >= 32 bytes for HS256.
    private static final String SECRET = "test-secret-that-is-definitely-long-enough-0123456789";

    private final JwtService jwt = new JwtService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7), "");
    private final User user = User.createOAuth("ada@example.com", "Ada", null);

    @Test
    void accessTokenRoundTripsWithClaims() {
        String token = jwt.issueAccessToken(user);

        Claims claims = jwt.parse(token);
        assertThat(jwt.userId(claims)).isEqualTo(user.getId());
        assertThat(claims.get("email", String.class)).isEqualTo("ada@example.com");
        assertThat(claims.get("name", String.class)).isEqualTo("Ada");
        assertThat(jwt.isRefresh(claims)).isFalse();
    }

    @Test
    void refreshTokenIsMarkedAsRefresh() {
        Claims claims = jwt.parse(jwt.issueRefreshToken(user));

        assertThat(jwt.userId(claims)).isEqualTo(user.getId());
        assertThat(jwt.isRefresh(claims)).isTrue();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwt.issueAccessToken(user);
        // Flip the last character of the signature — verification must fail.
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        JwtService other = new JwtService(
                "a-totally-different-secret-key-value-9876543210abcdef",
                Duration.ofMinutes(15), Duration.ofDays(7), "");
        String foreignToken = other.issueAccessToken(user);

        assertThatThrownBy(() -> jwt.parse(foreignToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        // Negative TTL → the token is born already expired.
        JwtService shortLived = new JwtService(SECRET, Duration.ofSeconds(-10), Duration.ofSeconds(-10), "");
        String token = shortLived.issueAccessToken(user);

        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }
}
