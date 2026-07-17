package org.cj.server.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/auth/register}. The annotations are Bean Validation
 * constraints: when a controller marks this {@code @Valid}, Spring checks them before
 * our code runs and, on failure, hands a {@code MethodArgumentNotValidException} to the
 * global handler (→ 400 with per-field messages). So the service can assume the data is
 * already well-formed.
 *
 * <p>The password max of 72 isn't arbitrary — BCrypt only hashes the first 72 <em>bytes</em>
 * of input and silently ignores the rest, so allowing longer would be a false promise.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72, message = "password must be 8–72 characters") String password,
        @NotBlank @Size(max = 120) String name) {
}
