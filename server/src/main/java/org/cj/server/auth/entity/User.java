package org.cj.server.auth.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user account. Maps to the {@code app_user} table (see {@code V2__app_user.sql}).
 *
 * <p>Design decisions worth knowing (from {@code project-scope.md}):
 * <ul>
 *   <li><b>App-generated UUID primary key</b> — we set the id in Java with
 *       {@link UUID#randomUUID()} rather than letting the DB assign a serial. That
 *       avoids a round-trip to learn the id and lets the client optimistically create
 *       rows later. So there is deliberately no {@code @GeneratedValue}.</li>
 *   <li><b>{@code passwordHash} is nullable</b> — OAuth-only accounts have no password.</li>
 *   <li><b>{@code email} is stored lowercased</b> and unique, so lookups and the unique
 *       constraint are case-insensitive in practice. Lowercasing happens in the service.</li>
 *   <li><b>Timestamps are {@link Instant} (UTC)</b> mapped to {@code timestamptz}.</li>
 * </ul>
 *
 * <p>This is a plain JPA entity — no Spring Security {@code UserDetails} here. We keep the
 * persistence model separate from the security adapter; the JWT layer (M1.3+) will bridge
 * them. A protected no-arg constructor exists only because JPA/Hibernate requires one.
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    /** BCrypt hash; null for OAuth-only accounts. Never the plaintext password. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 120)
    private String name;

    /** Avatar URL from an OAuth provider; null for email/password signups. */
    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. Do not use directly — prefer {@link #create}. */
    protected User() {
    }

    private User(UUID id, String email, String passwordHash, String name, String imageUrl, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.imageUrl = imageUrl;
        this.createdAt = createdAt;
    }

    /**
     * Factory for a brand-new email/password user. Generates the id and stamps
     * {@code createdAt} now, so callers can't forget either. The caller is
     * responsible for passing an already-lowercased email and an already-hashed
     * password (the entity never sees plaintext).
     */
    public static User create(String email, String passwordHash, String name) {
        return new User(UUID.randomUUID(), email, passwordHash, name, null, Instant.now());
    }

    /**
     * Factory for an OAuth-only account (e.g. Google): no password, but an avatar URL from the
     * provider. The caller passes an already-lowercased email.
     */
    public static User createOAuth(String email, String name, String imageUrl) {
        return new User(UUID.randomUUID(), email, null, name, imageUrl, Instant.now());
    }

    /**
     * Backfill the avatar from an OAuth provider on an existing account — only when we don't
     * already have one, so we never clobber a picture the user set another way.
     */
    public void linkOAuthAvatar(String imageUrl) {
        if (this.imageUrl == null && imageUrl != null) {
            this.imageUrl = imageUrl;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
