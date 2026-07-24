package org.cj.server.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/auth/google} — the Google Sign-In ID token (a JWT) the browser
 * obtained from Google Identity Services. The backend verifies it and returns the usual
 * {@link AuthResponse}.
 */
public record GoogleLoginRequest(@NotBlank String idToken) {
}
