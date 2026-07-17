package org.cj.server.auth.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.cj.server.auth.service.JwtService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs once per request, before the rest of Spring Security. Its job: if the request carries
 * a valid {@code Authorization: Bearer <accessToken>} header, mark the request authenticated
 * by putting an {@link AuthPrincipal} into the {@link SecurityContextHolder}. From there,
 * Spring's {@code authenticated()} rule lets the request through and controllers can read the
 * principal.
 *
 * <p>What it deliberately does <b>not</b> do: reject bad tokens itself. If a token is missing,
 * malformed, expired, or is a <em>refresh</em> token (not valid for API access), the filter
 * simply leaves the request unauthenticated and moves on. Spring Security then blocks any
 * protected endpoint with a 401 via the configured entry point. This "authenticate if you
 * can, otherwise stay anonymous" shape is the standard, safe pattern — a public route like
 * {@code /api/health} still works with no token at all.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwt.parse(token); // verifies signature + expiry
                // Refresh tokens must never authenticate an API call — they only buy new
                // access tokens at /api/auth/refresh.
                if (!jwt.isRefresh(claims)) {
                    authenticate(request, claims);
                }
            } catch (JwtException | IllegalArgumentException ex) {
                // Invalid/expired/tampered token → stay anonymous; the security layer
                // will return 401 for anything that needs auth. Never surface details.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, Claims claims) {
        AuthPrincipal principal = new AuthPrincipal(
                jwt.userId(claims),
                claims.get("email", String.class));

        // No authorities in M1 — roles are per-board and arrive in M4. An empty list still
        // counts as "authenticated", which is all these endpoints require for now.
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** Pulls the raw token out of the {@code Authorization: Bearer <token>} header, or null. */
    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        return null;
    }
}
