package org.cj.server.support;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import org.cj.server.auth.entity.User;
import org.cj.server.auth.repository.UserRepository;
import org.cj.server.auth.service.JwtService;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared base for the HTTP integration tests. Boots the whole app (real Postgres + security
 * filter chain) and gives every subclass the common wiring plus test-user helpers.
 *
 * <p>Sign-in is Google-only, so tests can't mint a user by POSTing to a password endpoint any
 * more. Instead these helpers create the account <b>in-process</b> ({@link UserRepository}) and
 * issue a token directly ({@link JwtService}) — faster than an HTTP round-trip and independent of
 * the sign-in mechanism. A test that specifically exercises the Google sign-in flow (or the
 * sign-in event, e.g. pending-invite resolution) should still drive {@code POST /api/auth/google}
 * with a mocked {@code GoogleTokenVerifier}.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper om;

    @Autowired
    protected UserRepository users;

    @Autowired
    protected JwtService jwt;

    /** A unique email per call — the DB persists between test runs, so tests must not collide. */
    protected String uniqueEmail() {
        return "u-" + UUID.randomUUID() + "@example.com";
    }

    /** Create a fresh (OAuth-only) account directly in the DB and return it. */
    protected User createUser() {
        return users.save(User.createOAuth(uniqueEmail(), "Test User", null));
    }

    /** An access token for an existing user. */
    protected String tokenFor(User user) {
        return jwt.issueAccessToken(user);
    }

    /** Create a fresh account and return an access token for it. */
    protected String newUserToken() {
        return tokenFor(createUser());
    }

    /** Attach a Bearer token to a request builder. */
    protected MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }
}
