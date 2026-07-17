package org.cj.server.auth.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import org.cj.server.auth.dto.UserResponse;
import org.cj.server.auth.security.AuthPrincipal;
import org.cj.server.auth.security.JwtAuthenticationFilter;
import org.cj.server.auth.service.AuthService;

/**
 * "Who am I?" — returns the currently authenticated user. This is the endpoint that proves
 * the whole JWT loop works: it's <em>not</em> public, so reaching it at all means the
 * {@link JwtAuthenticationFilter} accepted a valid access token and populated the principal.
 *
 * <p>{@code @AuthenticationPrincipal} injects the {@link AuthPrincipal} the filter stored.
 * We reload the user by id so the response reflects the current name/avatar, not whatever
 * was baked into the token when it was issued.
 */
@RestController
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/api/me")
    public UserResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        return UserResponse.from(authService.getById(principal.userId()));
    }
}
