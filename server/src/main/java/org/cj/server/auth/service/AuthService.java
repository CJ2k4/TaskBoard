package org.cj.server.auth.service;

import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.auth.dto.LoginRequest;
import org.cj.server.auth.dto.RegisterRequest;
import org.cj.server.auth.entity.User;
import org.cj.server.auth.repository.UserRepository;
import org.cj.server.common.exception.ConflictException;

/**
 * Registration and login logic. Deliberately HTTP-agnostic: it takes validated request
 * DTOs, talks to the {@link UserRepository} and {@link PasswordEncoder}, and returns the
 * {@link User} (or throws). The controller (M1.4) turns the result into tokens + JSON.
 *
 * <p>Keeping this in a service — not the controller — means the same logic is reusable
 * (e.g. OAuth login can call in) and unit-testable without spinning up the web layer.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder,
                       ApplicationEventPublisher events) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    /**
     * Create a new email/password account. Fails with a 409 {@link ConflictException} if
     * the email is already taken. The password is BCrypt-hashed before it ever touches the
     * database — we never persist plaintext.
     */
    @Transactional
    public User register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        if (users.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }
        String hash = passwordEncoder.encode(req.password());
        User user = users.save(User.create(email, hash, req.name().trim()));
        // Announce the sign-in so other features can react (e.g. pending-invite resolution).
        // Published inside the transaction: listeners join it, so activation is atomic with
        // the registration itself.
        events.publishEvent(new UserSignedInEvent(user.getId(), user.getEmail()));
        return user;
    }

    /**
     * Verify credentials and return the user. Throws {@link BadCredentialsException} (→ 401)
     * for both "no such email" and "wrong password" — same exception, so the caller can't
     * tell the two apart (prevents user-enumeration).
     *
     * <p>Note we still run {@code matches()} even when the user is missing? No — we short
     * out on empty. The generic error is what protects us; a constant-time dance isn't
     * warranted at this scale.
     */
    @Transactional(readOnly = true)
    public User authenticate(LoginRequest req) {
        String email = normalizeEmail(req.email());
        User user = users.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        // OAuth-only accounts have no password hash — they can't log in via password.
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        // This method runs in a read-only transaction, so any listener that writes (invite
        // resolution) must open its own transaction (REQUIRES_NEW on the listener side).
        events.publishEvent(new UserSignedInEvent(user.getId(), user.getEmail()));
        return user;
    }

    /**
     * Load a user by id. Used by the refresh flow and {@code /api/me}: the token already
     * proved who they are, but the account could have been deleted since. We throw the same
     * generic {@link BadCredentialsException} (→ 401) rather than a 404, so a stale token
     * just reads as "not authenticated".
     */
    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new BadCredentialsException("Account no longer exists"));
    }

    /** Emails are stored lowercased + trimmed so lookups and uniqueness are consistent. */
    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
