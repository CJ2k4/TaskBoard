package org.cj.server.common.exception;

import org.cj.server.common.dto.ApiError;

/**
 * Thrown when a request conflicts with the current state — e.g. registering an email
 * that already has an account. The global exception handler turns this into a 409
 * with the standard {@link ApiError} body, so services can throw it without knowing
 * anything about HTTP. Mirror of {@link NotFoundException}, different status.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
