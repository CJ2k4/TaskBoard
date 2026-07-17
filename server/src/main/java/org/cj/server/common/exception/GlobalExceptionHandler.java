package org.cj.server.common.exception;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.cj.server.common.dto.ApiError;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns exceptions thrown anywhere in the app into one consistent JSON error shape
 * ({@link ApiError}), so the frontend can parse <em>every</em> failure the same way.
 *
 * <p>Why centralize this? Without it, each controller would have to catch its own
 * exceptions and hand-build error responses — inconsistent, repetitive, and easy to
 * forget. A {@code @RestControllerAdvice} is Spring's "catch-all" layer: any exception
 * that bubbles out of a controller lands here, gets matched to the most specific
 * {@code @ExceptionHandler} method by type, and is converted to a tidy {@link ApiError}.
 * Services can therefore just {@code throw new NotFoundException(...)} and stay ignorant
 * of HTTP entirely.
 *
 * <p>The handlers are ordered conceptually from most-specific to least-specific; Spring
 * always dispatches to the closest matching exception type, with {@link #handleUnexpected}
 * as the last-resort net for anything we didn't anticipate.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 404 — a service asked for a resource that doesn't exist. This is an expected,
     * client-caused outcome (bad id in the URL), so we don't log it as an error.
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /**
     * 409 — the request conflicts with existing state, e.g. an email that's already
     * registered. Client-caused and expected, so no error-level logging.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * 401 — login failed. We deliberately return one generic message for both "no such
     * email" and "wrong password": revealing which one was wrong lets an attacker probe
     * for which emails have accounts (user enumeration).
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex,
                                                        HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", request);
    }

    /**
     * 400 — bean-validation (@Valid) rejected a request DTO. We unpack each invalid
     * field into {@link ApiError.FieldError} so the frontend can show messages next to
     * the offending inputs rather than one opaque "bad request".
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), messageFor(fe)))
                .toList();

        ApiError body = new ApiError(
                java.time.Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Validation failed",
                request.getRequestURI(),
                fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 400 — a caller passed something the service considers illegal (e.g. a bad argument
     * a DTO annotation couldn't express). Also client-caused, so no error-level logging.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * 500 — the catch-all for anything unforeseen (bugs, downstream failures). We log
     * the full stack trace here because it's <em>our</em> fault, but deliberately do NOT
     * leak the exception message to the client — that could expose internals. The client
     * just gets a generic "Internal Server Error".
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    /** Builds the standard single-message {@link ApiError} body wrapped in a response. */
    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    /** Falls back to a generic message if a validation annotation didn't supply one. */
    private String messageFor(FieldError fe) {
        return fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid value";
    }
}
