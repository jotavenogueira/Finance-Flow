package com.financeflow.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.GoogleIdentity;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * O Google valida o código e a assinatura do ID token. Tokens de acesso Google
 * são usados somente nesta chamada; não são armazenados nem enviados à interface.
 */
public class GoogleOpenIdProvider implements GoogleIdentityProvider {

    static final String AUTHORIZATION_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GoogleOAuthConfig config;
    private final String tokenUrl;
    private final HttpClient client;
    private final DefaultJWTProcessor<SecurityContext> tokens;

    public GoogleOpenIdProvider(GoogleOAuthConfig config) {
        this(config, TOKEN_URL, JWKS_URL, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    GoogleOpenIdProvider(GoogleOAuthConfig config, String tokenUrl, String jwksUrl, HttpClient client) {
        this.config = config;
        this.tokenUrl = tokenUrl;
        this.client = client;
        JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.<SecurityContext>create(URI.create(jwksUrl).toURL(),
                    new DefaultResourceRetriever((int) TIMEOUT.toMillis(), (int) TIMEOUT.toMillis())).build();
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException("URL de chaves Google inválida", exception);
        }
        this.tokens = new DefaultJWTProcessor<>();
        this.tokens.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        // Exige as claims básicas e valida exp/nbf; emissor e público são conferidos abaixo.
        this.tokens.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>((JWTClaimsSet) null,
                Set.of("iss", "sub", "aud", "exp")));
    }

    @Override
    public String authorizationUrl(String state, String verifier, String nonce) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", config.clientId());
        parameters.put("redirect_uri", config.redirectUrl());
        parameters.put("response_type", "code");
        parameters.put("scope", "openid email profile");
        parameters.put("state", state);
        parameters.put("access_type", "online");
        parameters.put("code_challenge", pkceChallenge(verifier));
        parameters.put("code_challenge_method", "S256");
        parameters.put("nonce", nonce);
        parameters.put("prompt", "select_account");
        return AUTHORIZATION_URL + "?" + form(parameters);
    }

    @Override
    public GoogleIdentity identity(String code, String verifier, String nonce) {
        String rawToken = exchange(code, verifier);
        JWTClaimsSet claims;
        try {
            claims = tokens.process(rawToken, null);
        } catch (Exception exception) {
            throw invalid();
        }
        String tokenNonce = claimString(claims, "nonce");
        if (nonce == null || nonce.isEmpty() || tokenNonce == null || !MessageDigest.isEqual(
                tokenNonce.getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8))) {
            throw invalid();
        }
        List<String> audience = claims.getAudience();
        String authorizedParty = claimString(claims, "azp");
        Object emailVerified = claims.getClaim("email_verified");
        if (!ISSUERS.contains(claims.getIssuer()) || !audience.contains(config.clientId())
                || !Boolean.TRUE.equals(emailVerified)
                || (authorizedParty != null && !authorizedParty.isEmpty() && !authorizedParty.equals(config.clientId()))
                || (audience.size() > 1 && !config.clientId().equals(authorizedParty))) {
            throw invalid();
        }
        return new GoogleIdentity(claims.getSubject(), claimString(claims, "email"), claimString(claims, "name"));
    }

    private String exchange(String code, String verifier) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("grant_type", "authorization_code");
        parameters.put("code", code);
        parameters.put("code_verifier", verifier);
        parameters.put("redirect_uri", config.redirectUrl());
        parameters.put("client_id", config.clientId());
        parameters.put("client_secret", config.clientSecret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(parameters)))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw invalid();
            }
            JsonNode idToken = JSON.readTree(response.body()).path("id_token");
            if (!idToken.isTextual() || idToken.asText().isEmpty()) {
                throw invalid();
            }
            return idToken.asText();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw invalid();
        } catch (DomainException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    static String pkceChallenge(String verifier) {
        byte[] digest = Tokens.sha256(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String claimString(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value instanceof String text ? text : null;
    }

    private static String form(Map<String, String> parameters) {
        return parameters.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static DomainException invalid() {
        return new DomainException(DomainError.INVALID_CREDENTIALS);
    }
}
