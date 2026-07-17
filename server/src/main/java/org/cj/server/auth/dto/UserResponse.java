package org.cj.server.auth.dto;

import java.time.Instant;
import java.util.UUID;

import org.cj.server.auth.entity.User;

/**
 * The public shape of a user in API responses. Note what's absent: {@code passwordHash}.
 * We map from the entity through this DTO precisely so a secret field can never leak into
 * JSON by accident — the response can only contain what we list here.
 */
public record UserResponse(
        UUID id,
        String email,
        String name,
        String imageUrl,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getImageUrl(),
                user.getCreatedAt());
    }
}
