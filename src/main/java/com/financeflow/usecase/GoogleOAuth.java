package com.financeflow.usecase;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.GoogleIdentity;
import com.financeflow.model.GoogleOAuthChallenge;
import java.time.Duration;
import java.time.Instant;

/** Fluxo de login Google. Quando não configurado, enabled() é falso e nada é aceito. */
public class GoogleOAuth {

    public static final Duration CHALLENGE_DURATION = Duration.ofMinutes(10);

    private final GoogleOAuthConfig config;
    private final GoogleOAuthStore store;
    private final GoogleIdentityProvider provider;

    public GoogleOAuth(GoogleOAuthConfig config, GoogleOAuthStore store, GoogleIdentityProvider provider) {
        boolean valid = config.enabled();
        if (valid) {
            try {
                config.validate();
            } catch (IllegalArgumentException exception) {
                valid = false;
            }
        }
        this.config = valid ? config : GoogleOAuthConfig.DISABLED;
        this.store = store;
        this.provider = provider;
    }

    public static GoogleOAuth disabled() {
        return new GoogleOAuth(GoogleOAuthConfig.DISABLED, null, null);
    }

    public boolean enabled() {
        return config.enabled();
    }

    public String canonicalOrigin() {
        return enabled() ? config.canonicalOrigin() : "";
    }

    public boolean usesHttps() {
        return enabled() && config.redirectUrl().startsWith("https://");
    }

    public String begin(String browserToken, int userId, String sessionToken) {
        if (!enabled() || !Tokens.valid(browserToken) || (userId > 0 && !Tokens.valid(sessionToken))) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        String state = Tokens.newToken();
        String nonce = Tokens.newToken();
        GoogleOAuthChallenge challenge = new GoogleOAuthChallenge(Tokens.hash(state), Tokens.hash(browserToken),
                nonce, Tokens.newToken(), userId, userId > 0 ? Tokens.hash(sessionToken) : "",
                Instant.now().plus(CHALLENGE_DURATION));
        store.createGoogleChallenge(challenge);
        return provider.authorizationUrl(state, challenge.verifier(), nonce);
    }

    public GoogleOAuthChallenge consume(String state, String browserToken) {
        if (!enabled() || !Tokens.valid(state) || !Tokens.valid(browserToken)) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        return store.consumeGoogleChallenge(Tokens.hash(state), Tokens.hash(browserToken));
    }

    public GoogleIdentity identity(GoogleOAuthChallenge challenge, String code) {
        if (!enabled() || code == null || code.isEmpty() || code.length() > 4096) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        return provider.identity(code, challenge.verifier(), challenge.nonce());
    }

    public boolean linkSessionActive(GoogleOAuthChallenge challenge) {
        return store.googleLinkSessionActive(challenge);
    }
}
