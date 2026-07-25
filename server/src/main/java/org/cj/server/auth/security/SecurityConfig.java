package org.cj.server.auth.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security configuration. The API is <b>stateless</b>: no sessions, no cookies — every
 * request proves itself with a JWT access token, which {@link JwtAuthenticationFilter}
 * validates and turns into an authenticated principal.
 *
 * <p>Route rules:
 * <ul>
 *   <li>{@code /api/health} and {@code /api/auth/**} — <b>public</b> (you can't have a token
 *       before you log in, and health is for uptime checks).</li>
 *   <li>{@code /ws/**} — <b>public handshake</b>, authenticated at the STOMP layer instead;
 *       see the comment on the rule below.</li>
 *   <li>everything else — <b>authenticated</b>: needs a valid access token, else 401 via
 *       {@link JwtAuthenticationEntryPoint}.</li>
 * </ul>
 *
 * <p>(Per-board <em>authorization</em> — who may edit which board — is a different concern
 * that lands in M4; M1 only answers "are you logged in at all?".)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Frontend origin allowed to call this API. Override with APP_CORS_ALLOWED_ORIGINS. */
    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          JwtAuthenticationEntryPoint authenticationEntryPoint) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Apply the CORS rules defined by the bean below.
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // CSRF protection guards session-cookie forms; a stateless token API
            // doesn't use those, so it's turned off (standard for JSON/JWT APIs).
            .csrf(csrf -> csrf.disable())
            // Never create an HTTP session — each request authenticates on its own.
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/api/auth/**").permitAll()
                // The WebSocket *handshake* is public because it has to be: the browser's
                // WebSocket API can't attach an Authorization header to the upgrade request.
                // The socket is not unprotected — StompAuthChannelInterceptor demands a valid
                // access token on the STOMP CONNECT frame and board membership on every
                // SUBSCRIBE, so an unauthenticated connection can open but can never hear
                // anything.
                .requestMatchers("/ws/**").permitAll()
                .anyRequest().authenticated())
            // Unauthenticated hits on protected routes → 401 JSON (not the default 403).
            .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
            // Read the JWT and set the principal before the username/password machinery runs.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
