package org.cj.server.auth.service;

import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.cj.server.auth.entity.User;
import org.cj.server.auth.repository.UserRepository;

/**
 * Sign-in logic. Deliberately HTTP-agnostic: it talks to the {@link UserRepository} and
 * {@link GoogleTokenVerifier} and returns the {@link User} (or throws); the controller turns the
 * result into tokens + JSON.
 *
 * <p>Sign-in is <b>Google-only</b> — email/password register/login was removed. The only entry
 * point is {@link #authenticateWithGoogle}; {@link #getById} backs the refresh flow and
 * {@code /api/me}.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final ApplicationEventPublisher events;

    public AuthService(UserRepository users, GoogleTokenVerifier googleTokenVerifier,
                       ApplicationEventPublisher events) {
        this.users = users;
        this.googleTokenVerifier = googleTokenVerifier;
        this.events = events;
    }

    /**
     * Sign in with a Google ID token: verify it, then find-or-create the account by its verified
     * email. An existing account is <b>linked</b> (logged into, avatar backfilled) — same email is
     * the same person — so a user who first registered with a password can also use Google, and
     * vice versa. New accounts are OAuth-only (no password). Publishes {@link UserSignedInEvent}
     * exactly as register/login do, so pending-invite resolution fires the same way.
     *
     * @throws org.springframework.security.authentication.BadCredentialsException the token is
     *         invalid, or its email isn't verified by Google (→ 401)
     */
    @Transactional
    public User authenticateWithGoogle(String idToken) {
        GoogleTokenVerifier.GoogleAccount account = googleTokenVerifier.verify(idToken);
        if (!account.emailVerified()) {
            // Never trust an unverified email — it's the whole basis of email-linking.
            throw new BadCredentialsException("Google account email is not verified");
        }
        String email = normalizeEmail(account.email());
        String name = account.name() != null && !account.name().isBlank()
                ? account.name().trim()
                : email;

        User user = users.findByEmail(email)
                .map(existing -> {
                    existing.linkOAuthAvatar(account.imageUrl());
                    return existing;
                })
                .orElseGet(() -> users.save(User.createOAuth(email, name, account.imageUrl())));

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
