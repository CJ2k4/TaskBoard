package org.cj.server.auth.service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

/**
 * Verifies a Google Sign-In <b>ID token</b> and extracts the account behind it. The
 * {@link GoogleIdTokenVerifier} fetches and caches Google's public signing certificates and
 * checks the token's signature, issuer, expiry, and — crucially — that its {@code aud} claim
 * equals <b>our</b> OAuth client id, so a token minted for some other app can't be replayed here.
 *
 * <p>Kept as a thin wrapper so {@link AuthService} deals only with a plain {@link GoogleAccount}
 * and never imports Google types. A blank client id means Google isn't configured for this
 * deployment — an {@link IllegalStateException} (→ 500) rather than a misleading "bad token".
 */
@Component
public class GoogleTokenVerifier {

    /** Minimal view of a verified Google account — just what account resolution needs. */
    public record GoogleAccount(String email, String name, String imageUrl, boolean emailVerified) {
    }

    private final boolean configured;
    private final GoogleIdTokenVerifier verifier;

    public GoogleTokenVerifier(@Value("${app.google.client-id:}") String clientId) {
        this.configured = !clientId.isBlank();
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                // Empty audience list would reject everything anyway, but we guard explicitly below.
                .setAudience(configured ? List.of(clientId) : List.of())
                .build();
    }

    /**
     * Verify a raw ID token string and return the account. Throws {@link BadCredentialsException}
     * (→ 401) for any token that is missing, malformed, expired, wrongly-audienced, or otherwise
     * unverifiable.
     */
    public GoogleAccount verify(String idToken) {
        if (!configured) {
            throw new IllegalStateException("Google sign-in is not configured (set GOOGLE_CLIENT_ID)");
        }
        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (GeneralSecurityException | IOException e) {
            throw new BadCredentialsException("Could not verify Google token");
        }
        if (token == null) {
            throw new BadCredentialsException("Invalid Google token");
        }
        GoogleIdToken.Payload payload = token.getPayload();
        return new GoogleAccount(
                payload.getEmail(),
                (String) payload.get("name"),
                (String) payload.get("picture"),
                Boolean.TRUE.equals(payload.getEmailVerified()));
    }
}
