package org.cj.server.common;

/**
 * Thrown by services when a requested resource doesn't exist. The global exception
 * handler turns this into a 404 with the standard {@link ApiError} body, so services
 * can just {@code throw new NotFoundException("Board not found")} without knowing
 * anything about HTTP.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
