package com.financeflow.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.GoogleIdentity;
import com.financeflow.model.GoogleOAuthChallenge;
import com.financeflow.model.User;
import com.financeflow.usecase.AuthStore;
import com.financeflow.usecase.AuthUsecase;
import com.financeflow.usecase.GoogleAuthStore;
import com.financeflow.usecase.GoogleIdentityProvider;
import com.financeflow.usecase.GoogleOAuth;
import com.financeflow.usecase.GoogleOAuthConfig;
import com.financeflow.usecase.GoogleOAuthStore;
import com.financeflow.usecase.Tokens;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;

class GoogleAuthControllerTest {

    static class ChallengeStore implements GoogleOAuthStore {
        GoogleOAuthChallenge challenge;
        boolean consumed;
        int creates;
        boolean active = true;

        @Override
        public void createGoogleChallenge(GoogleOAuthChallenge challenge) {
            this.challenge = challenge;
            creates++;
        }

        @Override
        public GoogleOAuthChallenge consumeGoogleChallenge(String stateHash, String browserHash) {
            if (consumed || challenge == null || !challenge.stateHash().equals(stateHash)
                    || !challenge.browserHash().equals(browserHash) || !challenge.expiresAt().isAfter(Instant.now())) {
                throw new DomainException(DomainError.INVALID_CREDENTIALS);
            }
            consumed = true;
            return challenge;
        }

        @Override
        public boolean googleLinkSessionActive(GoogleOAuthChallenge challenge) {
            return active;
        }
    }

    static class Provider implements GoogleIdentityProvider {
        String state;
        int calls;
        RuntimeException failure;

        @Override
        public String authorizationUrl(String state, String verifier, String nonce) {
            this.state = state;
            return "https://accounts.google.com/authorize?state=" + state;
        }

        @Override
        public GoogleIdentity identity(String code, String verifier, String nonce) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return new GoogleIdentity("stable-subject", "ana@example.com", "Ana");
        }
    }

    static class AccountStore implements AuthStore, GoogleAuthStore {
        int sessions;
        int links;
        int linkedUser;
        RuntimeException failure;
        String savedHash;

        @Override
        public User createUser(User user) {
            throw new UnsupportedOperationException();
        }

        @Override
        public User getUserByEmail(String email) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createSession(String tokenHash, int userId, Instant expiresAt) {
            sessions++;
            savedHash = tokenHash;
        }

        @Override
        public User getUserBySession(String tokenHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteSession(String tokenHash) {
        }

        @Override
        public User resolveGoogleUser(GoogleIdentity identity) {
            if (failure != null) {
                throw failure;
            }
            return new User(7, "Ana", "ana@example.com", "");
        }

        @Override
        public void linkGoogleUser(int userId, GoogleIdentity identity) {
            links++;
            linkedUser = userId;
            if (failure != null) {
                throw failure;
            }
        }
    }

    final ChallengeStore store = new ChallengeStore();
    final Provider provider = new Provider();
    final AccountStore accounts = new AccountStore();
    final MockMvc mvc = mvc(new GoogleOAuth(new GoogleOAuthConfig("test-client", "test-secret",
            "https://finance.example/auth/google/callback"), store, provider));

    private MockMvc mvc(GoogleOAuth flow) {
        HandlerInterceptor fakeLogin = new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                request.setAttribute(AuthInterceptor.AUTHENTICATED_USER, new User(7, "Ana", "ana@example.com", ""));
                return true;
            }
        };
        return MockMvcBuilders.standaloneSetup(new GoogleAuthController(new AuthUsecase(accounts), flow,
                        new SessionCookies("true")))
                .addMappedInterceptors(new String[] {"/auth/google/link/start"}, fakeLogin)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpServletResponse request(MockHttpServletRequestBuilder builder, Cookie... cookies) throws Exception {
        builder.secure(true).header("Host", "finance.example");
        if (cookies.length > 0) {
            builder.cookie(cookies);
        }
        return mvc.perform(builder).andReturn().getResponse();
    }

    private Cookie start(boolean link) throws Exception {
        MockHttpServletResponse response = link
                ? request(post("/auth/google/link/start"), new Cookie(SessionCookies.SESSION_COOKIE, Tokens.newToken()))
                : request(post("/auth/google/start"));
        assertThat(response.getStatus()).isEqualTo(303);
        assertThat(response.getHeader("Location")).startsWith("https://accounts.google.com/");
        String header = setCookie(response, SessionCookies.GOOGLE_BROWSER_COOKIE);
        assertThat(header).isNotNull();
        return new Cookie(SessionCookies.GOOGLE_BROWSER_COOKIE, header.substring(header.indexOf('=') + 1,
                header.indexOf(';')));
    }

    private String callbackPath() {
        return "/auth/google/callback?state=" + URLEncoder.encode(provider.state, StandardCharsets.UTF_8)
                + "&code=test-code";
    }

    private static String setCookie(MockHttpServletResponse response, String name) {
        return response.getHeaders("Set-Cookie").stream().filter(value -> value.startsWith(name + "="))
                .findFirst().orElse(null);
    }

    private static void assertRedirect(MockHttpServletResponse response, String location) {
        assertThat(response.getStatus()).isEqualTo(303);
        assertThat(response.getHeader("Location")).isEqualTo(location);
    }

    @Test
    void createsLaxCookieAndAuthenticatesOnce() throws Exception {
        MockHttpServletResponse started = request(post("/auth/google/start"));
        String header = setCookie(started, SessionCookies.GOOGLE_BROWSER_COOKIE);
        assertThat(header).contains("Path=/auth/google", "Max-Age=600", "Secure", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=");
        Cookie browser = new Cookie(SessionCookies.GOOGLE_BROWSER_COOKIE,
                header.substring(header.indexOf('=') + 1, header.indexOf(';')));

        MockHttpServletResponse response = request(get(callbackPath()), browser);
        assertRedirect(response, "/");
        String session = setCookie(response, SessionCookies.SESSION_COOKIE);
        assertThat(session).contains("HttpOnly", "Secure", "SameSite=Strict");
        String token = session.substring(session.indexOf('=') + 1, session.indexOf(';'));
        assertThat(token).isNotEmpty().isNotEqualTo(accounts.savedHash);
        assertThat(accounts.sessions).isEqualTo(1);

        assertRedirect(request(get(callbackPath()), browser), "/?auth_error=google_expired");
        assertThat(provider.calls).isEqualTo(1);
        assertThat(accounts.sessions).isEqualTo(1);
    }

    @Test
    void rejectsMissingMismatchedAndDuplicateCallbackBinding() throws Exception {
        for (String sample : List.of("missing cookie", "mismatched cookie", "missing state", "duplicated state",
                "duplicated code")) {
            GoogleAuthControllerTest fixture = new GoogleAuthControllerTest();
            Cookie browser = fixture.start(false);
            String path = fixture.callbackPath();
            Cookie[] cookies = {browser};
            switch (sample) {
                case "missing cookie" -> cookies = new Cookie[0];
                case "mismatched cookie" -> cookies = new Cookie[] {
                        new Cookie(SessionCookies.GOOGLE_BROWSER_COOKIE, Tokens.newToken())};
                case "missing state" -> path = "/auth/google/callback?code=test-code";
                case "duplicated state" -> path += "&state=other";
                default -> path += "&code=other";
            }
            MockHttpServletResponse response = fixture.request(get(path), cookies);
            assertRedirect(response, "/?auth_error=google_expired");
            assertThat(fixture.provider.calls).as(sample).isZero();
            assertThat(fixture.accounts.sessions).as(sample).isZero();
            assertThat(setCookie(response, SessionCookies.GOOGLE_BROWSER_COOKIE)).as(sample).contains("Max-Age=0");
        }
    }

    @Test
    void consumesDenialAndHidesProviderErrors() throws Exception {
        Cookie browser = start(false);
        MockHttpServletResponse response = request(get("/auth/google/callback?state="
                + URLEncoder.encode(provider.state, StandardCharsets.UTF_8)
                + "&error=access_denied&error_description=private-provider-data"), browser);
        assertRedirect(response, "/?auth_error=google_denied");
        assertThat(store.consumed).isTrue();
        assertThat(provider.calls).isZero();
        assertThat(response.getContentAsString()).doesNotContain("private-provider-data");

        GoogleAuthControllerTest fixture = new GoogleAuthControllerTest();
        browser = fixture.start(false);
        fixture.provider.failure = new IllegalStateException("sensitive-provider-token");
        response = fixture.request(get(fixture.callbackPath()), browser);
        assertRedirect(response, "/?auth_error=google_failed");
        assertThat(fixture.accounts.sessions).isZero();
        assertThat(response.getContentAsString()).doesNotContain("sensitive-provider-token");
    }

    @Test
    void requiresActiveOriginalSessionBeforeLinking() throws Exception {
        for (boolean active : new boolean[] {true, false}) {
            GoogleAuthControllerTest fixture = new GoogleAuthControllerTest();
            Cookie browser = fixture.start(true);
            fixture.store.active = active;
            // O cookie Strict da aplicação não acompanha o callback vindo de outro site.
            MockHttpServletResponse response = fixture.request(get(fixture.callbackPath()), browser);
            if (active) {
                assertRedirect(response, "/?auth_success=google_linked");
                assertThat(fixture.accounts.links).isEqualTo(1);
                assertThat(fixture.accounts.linkedUser).isEqualTo(7);
            } else {
                assertRedirect(response, "/?auth_error=google_expired");
                assertThat(fixture.accounts.links).isZero();
            }
            assertThat(fixture.accounts.sessions).isZero();
        }
    }

    @Test
    void returnsExplicitConflictWithoutCreatingSession() throws Exception {
        for (DomainError error : List.of(DomainError.GOOGLE_ACCOUNT_EXISTS, DomainError.GOOGLE_ACCOUNT_CONFLICT,
                DomainError.GOOGLE_EMAIL_MISMATCH)) {
            GoogleAuthControllerTest fixture = new GoogleAuthControllerTest();
            Cookie browser = fixture.start(false);
            fixture.accounts.failure = new DomainException(error);
            assertRedirect(fixture.request(get(fixture.callbackPath()), browser),
                    "/?auth_error=" + error.name().toLowerCase());
            assertThat(fixture.accounts.sessions).isZero();
        }
    }

    @Test
    void canonicalizesBeforeCreatingChallenge() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/auth/google/start").secure(true)
                .header("Host", "old-alias.example")).andReturn().getResponse();
        assertRedirect(response, "https://finance.example/");
        assertThat(store.creates).isZero();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void disabledProviderKeepsPasswordFlowAvailable() throws Exception {
        MockMvc disabled = mvc(GoogleOAuth.disabled());
        assertThat(disabled.perform(get("/auth/providers")).andReturn().getResponse().getContentAsString())
                .isEqualTo("{\"google\":false}");
        assertThat(disabled.perform(post("/auth/google/start")).andReturn().getResponse().getStatus()).isEqualTo(303);
        assertThat(disabled.perform(get("/auth/google/callback")).andReturn().getResponse().getStatus())
                .isEqualTo(303);
    }
}
