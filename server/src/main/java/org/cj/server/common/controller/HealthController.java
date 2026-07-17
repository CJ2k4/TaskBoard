package org.cj.server.common.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trivial liveness endpoint. Returns {"status":"ok"} so the frontend (and later
 * uptime checks) can confirm the backend is up and reachable. Public — see
 * {@code org.cj.server.auth.security.SecurityConfig}.
 */
@RestController
public class HealthController {

    /** A typed response body. Jackson serializes this record to {"status":"ok"}. */
    public record HealthStatus(String status) {}

    @GetMapping("/api/health")
    public HealthStatus health() {
        return new HealthStatus("ok");
    }
}
