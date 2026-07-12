package org.cj.server.common;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single JSON shape every error response uses, so the frontend can parse any
 * failure the same way. Example:
 *
 * <pre>{@code
 * {
 *   "timestamp": "2026-07-12T12:00:00Z",
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "Board not found",
 *   "path": "/api/boards/123"
 * }
 * }</pre>
 *
 * {@code fieldErrors} is only present on validation failures (see {@link #JsonInclude}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    /** One invalid field and why, for form/validation errors. */
    public record FieldError(String field, String message) {}

    /** Convenience factory for the common case with no field-level detail. */
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, null);
    }
}
