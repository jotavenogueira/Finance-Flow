package com.financeflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.GoogleIdentity;
import com.financeflow.model.GoogleOAuthChallenge;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class GoogleOAuthTest {

    static class ChallengeMemory implements GoogleOAuthStore {
        final Map<String, GoogleOAuthChallenge> challenges = new HashMap<>();
        boolean active = true;
        int creates;
        int consumes;

        @Override
        public void createGoogleChallenge(GoogleOAuthChallenge challenge) {
            creates++;
            challenges.put(challenge.stateHash(), challenge);
        }

        @Override
        public GoogleOAuthChallenge consumeGoogleChallenge(String stateHash, String browserHash) {
            consumes++;
            GoogleOAuthChallenge challenge = challenges.get(stateHash);
            if (challenge == null || !challenge.browserHash().equals(browserHash)
                    || !challenge.expiresAt().isAfter(Instant.now()) || (challenge.linkUserId() > 0 && !active)) {
                throw new DomainException(DomainError.INVALID_CREDENTIALS);
            }
            challenges.remove(stateHash);
            return challenge;
        }

        @Override
        public boolean googleLinkSessionActive(GoogleOAuthChallenge challenge) {
            return active;
        }
    }

    static class ProviderMemory implements GoogleIdentityProvider {
        String state;
        String verifier;
        String nonce;

        @Override
        public String authorizationUrl(String state, String verifier, String nonce) {
            this.state = state;
            this.verifier = verifier;
            this.nonce = nonce;
            return "https://accounts.google.com/authorize?state=" + state;
        }

        @Override
        public GoogleIdentity identity(String code, String verifier, String nonce) {
            return new GoogleIdentity("", "", "");
        }
    }

    static GoogleOAuthConfig testConfig() {
        return new GoogleOAuthConfig("test-client", "local-test-secret", "https://finance.example/auth/google/callback");
    }

    private static void assertInvalidCredentials(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.error()).isEqualTo(DomainError.INVALID_CREDENTIALS));
    }

    @Test
    void challengeBindsBrowserAndCanOnlyBeConsumedOnce() {
        ChallengeMemory store = new ChallengeMemory();
        ProviderMemory provider = new ProviderMemory();
        GoogleOAuth flow = new GoogleOAuth(testConfig(), store, provider);
        String browser = Tokens.newToken();
        String session = Tokens.newToken();
        Instant before = Instant.now();
        flow.begin(browser, 7, session);

        GoogleOAuthChallenge saved = store.challenges.get(Tokens.hash(provider.state));
        assertThat(saved.browserHash()).isEqualTo(Tokens.hash(browser)).isNotEqualTo(browser);
        assertThat(saved.linkSessionHash()).isEqualTo(Tokens.hash(session));
        assertThat(saved.linkUserId()).isEqualTo(7);
        assertThat(saved.stateHash()).isNotEqualTo(provider.state);
        assertThat(saved.nonce()).isNotEmpty();
        assertThat(saved.verifier()).isNotEmpty();
        assertThat(saved.expiresAt()).isAfter(before.plus(Duration.ofMinutes(9)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(11)));

        assertInvalidCredentials(() -> flow.consume(provider.state, Tokens.newToken()));
        assertThat(store.challenges).hasSize(1);
        flow.consume(provider.state, browser);
        assertInvalidCredentials(() -> flow.consume(provider.state, browser));
    }

    @Test
    void challengeRejectsMalformedAndExpiredAttempts() {
        ChallengeMemory store = new ChallengeMemory();
        ProviderMemory provider = new ProviderMemory();
        GoogleOAuth flow = new GoogleOAuth(testConfig(), store, provider);
        String browser = Tokens.newToken();

        assertInvalidCredentials(() -> flow.begin("invalid", 0, ""));
        assertInvalidCredentials(() -> flow.begin(browser, 7, "invalid"));
        assertThat(store.creates).isZero();
        assertInvalidCredentials(() -> flow.consume("invalid", browser));
        assertThat(store.consumes).isZero();

        flow.begin(browser, 0, "");
        GoogleOAuthChallenge c = store.challenges.get(Tokens.hash(provider.state));
        store.challenges.put(c.stateHash(), new GoogleOAuthChallenge(c.stateHash(), c.browserHash(), c.nonce(),
                c.verifier(), c.linkUserId(), c.linkSessionHash(), Instant.now().minusSeconds(1)));
        assertInvalidCredentials(() -> flow.consume(provider.state, browser));
    }

    @Test
    void configurationAllowsOnlyExplicitSecureCallback() {
        for (String redirect : List.of("https://finance.example/auth/google/callback",
                "http://localhost:8081/auth/google/callback", "http://127.0.0.1:8081/auth/google/callback")) {
            new GoogleOAuthConfig("id", "secret", redirect).validate();
        }
        for (String redirect : List.of("http://finance.example/auth/google/callback",
                "https://user:password@finance.example/auth/google/callback", "https://finance.example/other",
                "https://finance.example/auth/google/callback?next=evil",
                "https://finance.example/auth/google/callback#fragment", "//finance.example/auth/google/callback")) {
            assertThatThrownBy(() -> new GoogleOAuthConfig("id", "secret", redirect).validate())
                    .as(redirect).isInstanceOf(IllegalArgumentException.class);
        }
        Map<String, String> environment = new HashMap<>();
        assertThat(GoogleOAuthConfig.fromEnvironment(environment::get).enabled()).isFalse();
        environment.put("GOOGLE_CLIENT_ID", "only-one-variable");
        assertThatThrownBy(() -> GoogleOAuthConfig.fromEnvironment(environment::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authorizationUsesCodePkceNonceAndIdentityOnlyScopes() {
        GoogleOpenIdProvider provider = new GoogleOpenIdProvider(testConfig());
        String verifier = Tokens.newToken();
        URI location = URI.create(provider.authorizationUrl("test-state", verifier, "test-nonce"));
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : location.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        assertThat(location.getHost()).isEqualTo("accounts.google.com");
        assertThat(query).containsEntry("response_type", "code").containsEntry("state", "test-state")
                .containsEntry("nonce", "test-nonce").containsEntry("code_challenge_method", "S256")
                .containsEntry("code_challenge", GoogleOpenIdProvider.pkceChallenge(verifier))
                .containsEntry("access_type", "online").containsEntry("scope", "openid email profile");
        assertThat(location.toString()).doesNotContain("local-test-secret");
    }

    @Test
    void identityValidatesRealSignedTokens() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").keyUse(KeyUse.SIGNATURE).generate();
        RSAKey wrongKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        AtomicReference<String> rawToken = new AtomicReference<>();
        AtomicInteger exchanges = new AtomicInteger();
        AtomicReference<String> badExchange = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> respond(exchange, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext("/token", exchange -> {
            exchanges.incrementAndGet();
            Map<String, String> form = new HashMap<>();
            for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                String[] parts = pair.split("=", 2);
                form.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            if (!"test-code".equals(form.get("code")) || !"test-verifier".equals(form.get("code_verifier"))
                    || !"test-client".equals(form.get("client_id"))
                    || !"local-test-secret".equals(form.get("client_secret"))
                    || !"https://finance.example/auth/google/callback".equals(form.get("redirect_uri"))) {
                badExchange.set("token exchange must send PKCE verifier and client credentials");
            }
            respond(exchange, "{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\",\"id_token\":\""
                    + rawToken.get() + "\"}");
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GoogleOpenIdProvider provider = new GoogleOpenIdProvider(testConfig(), base + "/token", base + "/jwks",
                    HttpClient.newHttpClient());
            record Case(String name, Consumer<Map<String, Object>> change, boolean badSignature, String nonce,
                        boolean valid) {
            }
            List<Case> cases = List.of(
                    new Case("valid", c -> { }, false, "test-nonce", true),
                    new Case("Google legacy issuer", c -> c.put("iss", "accounts.google.com"), false, "test-nonce", true),
                    new Case("wrong issuer", c -> c.put("iss", "https://attacker.example"), false, "test-nonce", false),
                    new Case("wrong audience", c -> c.put("aud", "different-client"), false, "test-nonce", false),
                    new Case("expired", c -> c.put("exp", Date.from(Instant.now().minus(Duration.ofHours(1)))),
                            false, "test-nonce", false),
                    new Case("wrong signature", c -> { }, true, "test-nonce", false),
                    new Case("wrong nonce", c -> c.put("nonce", "different-nonce"), false, "test-nonce", false),
                    new Case("missing nonce", c -> c.remove("nonce"), false, "test-nonce", false),
                    new Case("empty expected nonce", c -> { }, false, "", false),
                    new Case("unverified email", c -> c.put("email_verified", false), false, "test-nonce", false),
                    new Case("missing email verification", c -> c.remove("email_verified"), false, "test-nonce", false),
                    new Case("wrong authorized party", c -> c.put("azp", "different-client"), false, "test-nonce", false),
                    new Case("multiple audience missing party",
                            c -> c.put("aud", List.of("test-client", "another-client")), false, "test-nonce", false),
                    new Case("multiple audience correct party", c -> {
                        c.put("aud", List.of("test-client", "another-client"));
                        c.put("azp", "test-client");
                    }, false, "test-nonce", true));
            for (Case sample : cases) {
                Map<String, Object> claims = new HashMap<>();
                claims.put("iss", "https://accounts.google.com");
                claims.put("aud", "test-client");
                claims.put("sub", "stable-subject");
                claims.put("exp", Date.from(Instant.now().plus(Duration.ofHours(1))));
                claims.put("iat", Date.from(Instant.now()));
                claims.put("nonce", "test-nonce");
                claims.put("email", "ana@example.com");
                claims.put("email_verified", true);
                claims.put("name", "Ana");
                sample.change().accept(claims);
                rawToken.set(sign(sample.badSignature() ? wrongKey : key, claims));
                if (sample.valid()) {
                    GoogleIdentity identity = provider.identity("test-code", "test-verifier", sample.nonce());
                    assertThat(identity.subject()).as(sample.name()).isEqualTo("stable-subject");
                    assertThat(identity.email()).as(sample.name()).isEqualTo("ana@example.com");
                } else {
                    assertThatThrownBy(() -> provider.identity("test-code", "test-verifier", sample.nonce()))
                            .as(sample.name()).isInstanceOfSatisfying(DomainException.class,
                                    exception -> assertThat(exception.error()).isEqualTo(DomainError.INVALID_CREDENTIALS));
                }
            }
            assertThat(exchanges.get()).isEqualTo(cases.size());
            assertThat(badExchange.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    private static String sign(RSAKey key, Map<String, Object> claims) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        claims.forEach(builder::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key")
                .type(JOSEObjectType.JWT).build(), builder.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
