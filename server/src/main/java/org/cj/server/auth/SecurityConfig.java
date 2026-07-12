package org.cj.server.auth;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security baseline for M0.
 *
 * <p>By default Spring Security locks <em>every</em> endpoint and invents a random
 * login password on startup (that WARN line you saw in the logs). Here we take back
 * control: the API is stateless (JWT arrives in M1), CORS is opened for the Next.js
 * origin, and only {@code /api/health} is public for now. Everything else still
 * requires authentication — which nothing can satisfy yet, exactly as intended until
 * real auth lands.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Frontend origin allowed to call this API. Override with APP_CORS_ALLOWED_ORIGINS. */
    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

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
                .requestMatchers("/api/health").permitAll()
                .anyRequest().authenticated());
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
