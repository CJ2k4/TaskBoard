package org.cj.server.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/auth/login}. We only check that both fields are present —
 * no size/format rules here on purpose. Login must not leak what a valid password
 * looks like, and a wrong password should always come back as one generic 401, never
 * a 400 validation error that hints the input was "malformed".
 */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password) {
}
