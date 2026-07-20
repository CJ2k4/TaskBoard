package org.cj.server.realtime.security;

import org.springframework.messaging.MessagingException;

/**
 * Thrown when a STOMP frame fails authentication or authorization. Extending
 * {@link MessagingException} is what makes it work: Spring's STOMP handler catches it on the
 * inbound channel, sends the client an ERROR frame carrying the message, and closes the
 * session — so a refusal is terminal rather than a frame the client can simply retry.
 *
 * <p>The message reaches the client, so it says what was refused and never why in detail.
 */
public class StompSecurityException extends MessagingException {

    public StompSecurityException(String message) {
        super(message);
    }
}
