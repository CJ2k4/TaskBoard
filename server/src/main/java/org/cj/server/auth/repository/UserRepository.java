package org.cj.server.auth.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cj.server.auth.entity.User;

/**
 * Data access for {@link User}. Extending {@link JpaRepository} gives us
 * {@code save}, {@code findById}, {@code delete}, etc. for free — Spring Data
 * generates the implementation at runtime; we never write the SQL.
 *
 * <p>The two custom methods are <b>derived queries</b>: Spring Data parses the
 * method name and builds the query. {@code findByEmail} → {@code WHERE email = ?}.
 * We store emails lowercased, so callers must lowercase before calling.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Look up a user for login. Empty if no account has that (lowercased) email. */
    Optional<User> findByEmail(String email);

    /** Cheap existence check used to reject duplicate registrations. */
    boolean existsByEmail(String email);
}
