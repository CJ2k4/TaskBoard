package org.cj.server.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/auth/refresh}: the long-lived refresh token to exchange. */
public record RefreshRequest(@NotBlank String refreshToken) {
}
