package org.cj.server.auth.dto;

import org.cj.server.auth.entity.User;

/**
 * Response body for register / login / refresh: who you are plus the token pair to use.
 * The client stores the tokens and sends the access token as {@code Authorization: Bearer
 * <accessToken>} on subsequent requests.
 */
public record AuthResponse(
        UserResponse user,
        String accessToken,
        String refreshToken) {

    public static AuthResponse of(User user, TokenPair tokens) {
        return new AuthResponse(UserResponse.from(user), tokens.accessToken(), tokens.refreshToken());
    }
}
