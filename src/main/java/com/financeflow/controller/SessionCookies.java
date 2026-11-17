package com.financeflow.controller;

import com.financeflow.usecase.GoogleOAuth;
import com.financeflow.usecase.Session;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class SessionCookies {

    public static final String SESSION_COOKIE = "financeflow_session";
    public static final String GOOGLE_BROWSER_COOKIE = "financeflow_google_browser";

    private final boolean forceSecure;

    public SessionCookies(@Value("${SESSION_COOKIE_SECURE:false}") String secure) {
        this.forceSecure = "true".equalsIgnoreCase(secure.strip());
    }

    public void setSession(HttpServletRequest request, HttpServletResponse response, Session session) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION_COOKIE, session.token())
                .path("/").maxAge(Duration.ofHours(24)).httpOnly(true).sameSite("Strict")
                .secure(secure(request)).build().toString());
    }

    public void clearSession(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION_COOKIE, "")
                .path("/").maxAge(0).httpOnly(true).sameSite("Strict")
                .secure(secure(request)).build().toString());
    }

    /** Lax, para acompanhar o retorno do Google; vale somente para /auth/google. */
    public void setGoogleBrowser(HttpServletRequest request, HttpServletResponse response, GoogleOAuth google,
                                 String token, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(GOOGLE_BROWSER_COOKIE, token)
                .path("/auth/google").maxAge(maxAge).httpOnly(true).sameSite("Lax")
                .secure(secure(request) || google.usesHttps()).build().toString());
    }

    public static String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private boolean secure(HttpServletRequest request) {
        return request.isSecure() || forceSecure;
    }
}
