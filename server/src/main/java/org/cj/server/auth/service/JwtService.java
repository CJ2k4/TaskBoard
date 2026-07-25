package org.cj.server.auth.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.cj.server.auth.dto.TokenPair;
import org.cj.server.auth.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies JSON Web Tokens (JWTs). A JWT is a signed, self-describing string:
 * {@code header.payload.signature}. The payload holds claims (who the user is, when the
 * token expires); the signature is an HMAC over header+payload using our secret. Because
 * we can verify that signature, we trust a token without storing any session — that's what
 * makes auth <b>stateless</b>.
 *
 * <p>Two token kinds, distinguished by a {@code type} claim:
 * <ul>
 *   <li><b>access</b> — short-lived (~15 min), sent on every API request.</li>
 *   <li><b>refresh</b> — long-lived (~7 days), used only to mint new access tokens, so a
 *       stolen access token is useless quickly while the user still stays logged in.</li>
 * </ul>
 *
 * <p>We sign with HMAC-SHA, a symmetric scheme: the same secret signs and verifies. Fine
 * here because only our backend does both. jjwt auto-selects the strongest variant the key
 * length supports (HS256/384/512) — our 54-byte dev secret yields HS384. Anyone holding the
 * secret can forge tokens — hence it comes from config/env, never hard-coded.
 */
@Service
public class JwtService {

    /** Claim name marking a token as "access" or "refresh". */
    static final String CLAIM_TYPE = "type";
    static final String TYPE_ACCESS = "access";
    static final String TYPE_REFRESH = "refresh";

    /**
     * The insecure default from {@code application.properties}. Fine for local dev and tests, but
     * a fatal misconfiguration in production — anyone with the repo could forge tokens with it.
     */
    static final String INSECURE_DEFAULT_SECRET = "dev-only-insecure-secret-change-me-in-prod-0123456789";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-ttl}") Duration accessTtl,
            @Value("${app.jwt.refresh-ttl}") Duration refreshTtl,
            @Value("${spring.profiles.active:}") String activeProfiles) {
        // Refuse to boot in production with the public dev secret still in place. Local dev and
        // tests run without a "prod" profile, so they keep using the convenient default; a prod
        // deployment (SPRING_PROFILES_ACTIVE=prod) MUST supply a real JWT_SECRET or fail fast here.
        if (activeProfiles.contains("prod") && INSECURE_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "app.jwt.secret is the insecure dev default in a prod profile — set JWT_SECRET "
                            + "to a strong random value (>= 32 bytes).");
        }
        // hmacShaKeyFor throws if the secret is shorter than 256 bits (32 bytes) — a
        // built-in guard against a too-weak signing key.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    /** Convenience: mint both tokens for a user at once. */
    public TokenPair issueTokens(User user) {
        return new TokenPair(issueAccessToken(user), issueRefreshToken(user));
    }

    /** Access token — subject is the user id, plus email/name for convenience. */
    public String issueAccessToken(User user) {
        return baseToken(user, TYPE_ACCESS, accessTtl)
                .claim("email", user.getEmail())
                .claim("name", user.getName())
                .compact();
    }

    /** Refresh token — minimal: just identifies the user and its type. */
    public String issueRefreshToken(User user) {
        return baseToken(user, TYPE_REFRESH, refreshTtl).compact();
    }

    /**
     * Verify a token's signature and expiration and return its claims. Throws a
     * {@link io.jsonwebtoken.JwtException} (e.g. {@code ExpiredJwtException},
     * {@code SignatureException}) if the token is invalid, tampered, or expired —
     * callers treat any such failure as "not authenticated".
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** The user id a verified token was issued for. */
    public UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    /** True if the verified token is a refresh token (vs an access token). */
    public boolean isRefresh(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    private io.jsonwebtoken.JwtBuilder baseToken(User user, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key);
    }
}
