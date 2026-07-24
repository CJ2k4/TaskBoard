package org.cj.server.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.cj.server.auth.dto.LoginRequest;
import org.cj.server.auth.dto.RegisterRequest;
import org.cj.server.auth.entity.User;
import org.cj.server.auth.repository.UserRepository;
import org.cj.server.common.exception.ConflictException;

/**
 * Unit tests for {@link AuthService}. The repository is mocked (no database), but we use a
 * <b>real</b> {@link BCryptPasswordEncoder} so we're actually exercising hashing/verification
 * — the security-critical part — not a stub that always says "matches".
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository users;

    @Mock
    GoogleTokenVerifier googleTokenVerifier;

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    AuthService authService;

    @BeforeEach
    void setUp() {
        // No-op event publisher: these tests cover auth logic; the sign-in event's effect
        // (pending-invite resolution) has its own integration test. The Google verifier is an
        // unused mock here — the Google path has its own integration test.
        authService = new AuthService(users, passwordEncoder, googleTokenVerifier, event -> { });
    }

    @Test
    void registerHashesPasswordAndLowercasesEmail() {
        when(users.existsByEmail("ada@example.com")).thenReturn(false);
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = authService.register(
                new RegisterRequest("  Ada@Example.com ", "hunter2secret", "Ada"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User persisted = saved.getValue();

        assertThat(persisted.getEmail()).isEqualTo("ada@example.com"); // trimmed + lowercased
        assertThat(persisted.getName()).isEqualTo("Ada");
        // stored value is a hash, never the plaintext, and it verifies
        assertThat(persisted.getPasswordHash()).isNotEqualTo("hunter2secret");
        assertThat(passwordEncoder.matches("hunter2secret", persisted.getPasswordHash())).isTrue();
        assertThat(result).isSameAs(persisted);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(users.existsByEmail("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("ada@example.com", "hunter2secret", "Ada")))
                .isInstanceOf(ConflictException.class);

        verify(users, never()).save(any());
    }

    @Test
    void authenticateReturnsUserOnCorrectPassword() {
        User ada = User.create("ada@example.com", passwordEncoder.encode("hunter2secret"), "Ada");
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));

        User result = authService.authenticate(new LoginRequest("Ada@example.com", "hunter2secret"));

        assertThat(result).isSameAs(ada);
    }

    @Test
    void authenticateRejectsWrongPassword() {
        User ada = User.create("ada@example.com", passwordEncoder.encode("hunter2secret"), "Ada");
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));

        assertThatThrownBy(() -> authService.authenticate(
                new LoginRequest("ada@example.com", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticateRejectsUnknownEmail() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authenticate(
                new LoginRequest("nobody@example.com", "whatever12")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticateRejectsOAuthOnlyAccount() {
        User oauthUser = User.create("g@example.com", null, "Grace"); // no password hash
        when(users.findByEmail("g@example.com")).thenReturn(Optional.of(oauthUser));

        assertThatThrownBy(() -> authService.authenticate(
                new LoginRequest("g@example.com", "anything123")))
                .isInstanceOf(BadCredentialsException.class);
    }
}
