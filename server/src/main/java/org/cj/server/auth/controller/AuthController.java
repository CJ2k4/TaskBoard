package org.cj.server.auth.controller;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.cj.server.auth.dto.AuthResponse;
import org.cj.server.auth.dto.GoogleLoginRequest;
import org.cj.server.auth.dto.RefreshRequest;
import org.cj.server.auth.entity.User;
import org.cj.server.auth.service.AuthService;
import org.cj.server.auth.service.JwtService;

import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;

/**
 * Public auth endpoints under {@code /api/auth/**}, which {@code SecurityConfig} leaves open —
 * you can't present a token before you have one. Sign-in is <b>Google-only</b>: password
 * register/login was removed, so the only ways to obtain tokens are exchanging a Google ID token
 * ({@code /google}) or rotating an existing refresh token ({@code /refresh}).
 *
 * <p>Each returns the same {@link AuthResponse} (user + fresh token pair). The controller stays
 * thin: validate input (via {@code @Valid}), delegate to {@link AuthService} / {@link JwtService},
 * shape the response.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    /**
     * Sign in with Google: verify the ID token, find-or-create the account by verified email,
     * and return a token pair. 200 OK (or 401 on an invalid token). This is the only sign-in
     * path — there is no password login.
     */
    @PostMapping("/google")
    public AuthResponse google(@Valid @RequestBody GoogleLoginRequest req) {
        User user = authService.authenticateWithGoogle(req.idToken());
        return AuthResponse.of(user, jwtService.issueTokens(user));
    }

    /**
     * Exchange a valid refresh token for a fresh token pair (rotation — the old refresh
     * token's holder gets a new one too). Rejects anything that isn't a valid, non-expired
     * <em>refresh</em> token with a 401.
     */
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
        Claims claims;
        try {
            claims = jwtService.parse(req.refreshToken());
        } catch (RuntimeException ex) {
            // Malformed/expired/tampered → generic 401 (mapped from BadCredentialsException).
            throw new BadCredentialsException("Invalid or expired refresh token");
        }
        if (!jwtService.isRefresh(claims)) {
            // An access token was sent where a refresh token belongs.
            throw new BadCredentialsException("Not a refresh token");
        }
        User user = authService.getById(jwtService.userId(claims));
        return AuthResponse.of(user, jwtService.issueTokens(user));
    }
}
