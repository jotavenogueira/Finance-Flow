package com.financeflow.controller;

import com.financeflow.model.DomainException;
import com.financeflow.model.GoogleIdentity;
import com.financeflow.model.GoogleOAuthChallenge;
import com.financeflow.model.User;
import com.financeflow.usecase.AuthResult;
import com.financeflow.usecase.AuthUsecase;
import com.financeflow.usecase.GoogleOAuth;
import com.financeflow.usecase.Tokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GoogleAuthController {

    private final AuthUsecase auth;
    private final GoogleOAuth flow;
    private final SessionCookies cookies;

    public GoogleAuthController(AuthUsecase auth, GoogleOAuth flow, SessionCookies cookies) {
        this.auth = auth;
        this.flow = flow;
        this.cookies = cookies;
    }

    @GetMapping("/auth/providers")
    public Map<String, Boolean> providers() {
        return Map.of("google", flow.enabled());
    }

    @PostMapping("/auth/google/start")
    public ResponseEntity<Void> start(HttpServletRequest request, HttpServletResponse response) {
        return begin(request, response, 0, "");
    }

    @PostMapping("/auth/google/link/start")
    public ResponseEntity<Void> linkStart(@RequestAttribute(AuthInterceptor.AUTHENTICATED_USER) User user,
                                          HttpServletRequest request, HttpServletResponse response) {
        String token = SessionCookies.read(request, SessionCookies.SESSION_COOKIE);
        return begin(request, response, user.id(), token == null ? "" : token);
    }

    private ResponseEntity<Void> begin(HttpServletRequest request, HttpServletResponse response, int userId,
                                       String sessionToken) {
        if (!flow.enabled()) {
            return redirect("/?auth_error=google_failed");
        }
        // Um cookie criado em outro alias não acompanha o callback canônico.
        if (!HttpProtectionFilter.sameHost(request, flow.canonicalOrigin())) {
            return redirect(flow.canonicalOrigin() + "/");
        }
        String browserToken = Tokens.newToken();
        String location;
        try {
            location = flow.begin(browserToken, userId, sessionToken);
        } catch (RuntimeException exception) {
            return fail("google_failed");
        }
        cookies.setGoogleBrowser(request, response, flow, browserToken, GoogleOAuth.CHALLENGE_DURATION);
        return redirect(location);
    }

    @GetMapping("/auth/google/callback")
    public ResponseEntity<Void> callback(HttpServletRequest request, HttpServletResponse response) {
        cookies.setGoogleBrowser(request, response, flow, "", Duration.ZERO);
        String[] states = request.getParameterValues("state");
        String[] codes = request.getParameterValues("code");
        String[] errors = request.getParameterValues("error");
        if (!flow.enabled() || states == null || states.length != 1 || (codes != null && codes.length > 1)
                || (errors != null && errors.length > 1)) {
            return fail("google_expired");
        }
        String browserToken = SessionCookies.read(request, SessionCookies.GOOGLE_BROWSER_COOKIE);
        GoogleOAuthChallenge challenge;
        try {
            challenge = flow.consume(states[0], browserToken);
        } catch (RuntimeException exception) {
            return fail("google_expired");
        }
        String error = errors == null ? "" : errors[0];
        if (!error.isEmpty()) {
            return fail(error.equals("access_denied") ? "google_denied" : "google_failed");
        }
        GoogleIdentity identity;
        try {
            identity = flow.identity(challenge, codes == null ? "" : codes[0]);
        } catch (RuntimeException exception) {
            return fail("google_failed");
        }
        if (challenge.linkUserId() > 0) {
            boolean active;
            try {
                active = flow.linkSessionActive(challenge);
            } catch (RuntimeException exception) {
                active = false;
            }
            if (!active) {
                return fail("google_expired");
            }
            try {
                auth.linkGoogle(challenge.linkUserId(), identity);
            } catch (RuntimeException exception) {
                return fail(googleErrorCode(exception));
            }
            return redirect("/?auth_success=google_linked");
        }
        AuthResult result;
        try {
            result = auth.googleLogin(identity);
        } catch (RuntimeException exception) {
            return fail(googleErrorCode(exception));
        }
        cookies.setSession(request, response, result.session());
        return redirect("/");
    }

    static String googleErrorCode(RuntimeException exception) {
        if (exception instanceof DomainException domain) {
            return switch (domain.error()) {
                case GOOGLE_ACCOUNT_EXISTS -> "google_account_exists";
                case GOOGLE_ACCOUNT_CONFLICT -> "google_account_conflict";
                case GOOGLE_EMAIL_MISMATCH -> "google_email_mismatch";
                default -> "google_failed";
            };
        }
        return "google_failed";
    }

    private static ResponseEntity<Void> fail(String code) {
        return redirect("/?auth_error=" + code);
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).build();
    }
}
