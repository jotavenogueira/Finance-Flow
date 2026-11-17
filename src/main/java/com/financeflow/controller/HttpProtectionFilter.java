package com.financeflow.controller;

import com.financeflow.usecase.GoogleOAuth;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cabeçalhos de segurança, bloqueio de escritas vindas de outros sites, endereço
 * canônico para o login Google e limite de 1 MiB para o corpo das requisições.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpProtectionFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 1 << 20;
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; "
            + "form-action 'self' https://accounts.google.com; frame-ancestors 'none'";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final GoogleOAuth google;

    public HttpProtectionFilter(GoogleOAuth google) {
        this.google = google;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        if (crossOrigin(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"message\":\"Origem da requisição não permitida.\"}");
            return;
        }
        if (google.enabled() && "GET".equals(request.getMethod()) && "/".equals(request.getRequestURI())
                && !sameHost(request, google.canonicalOrigin())) {
            response.setStatus(307);
            response.setHeader("Location", google.canonicalOrigin() + "/");
            return;
        }
        chain.doFilter(new LimitedBodyRequest(request), response);
    }

    /** Mesma regra de http.CrossOriginProtection do Go para métodos que alteram dados. */
    static boolean crossOrigin(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null && !fetchSite.isEmpty()) {
            return !fetchSite.equals("same-origin") && !fetchSite.equals("none");
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isEmpty()) {
            return false;
        }
        try {
            return !hostHeader(request).equals(URI.create(origin).getRawAuthority());
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    /** Compara o Host da requisição com o host (e porta) de uma origem como https://exemplo.com. */
    static boolean sameHost(HttpServletRequest request, String origin) {
        String canonicalHost = origin.replaceFirst("^https?://", "");
        return hostHeader(request).equalsIgnoreCase(canonicalHost);
    }

    private static String hostHeader(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host != null && !host.isEmpty()) {
            return host;
        }
        int port = request.getServerPort();
        boolean defaultPort = port < 0 || (port == 80 && "http".equals(request.getScheme()))
                || (port == 443 && "https".equals(request.getScheme()));
        return defaultPort ? request.getServerName() : request.getServerName() + ":" + port;
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private ServletInputStream limited;

        LimitedBodyRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitedInputStream(super.getInputStream());
            }
            return limited;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long read;

        LimitedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws IOException {
            read += bytes;
            if (read > MAX_BODY_BYTES) {
                throw new IOException("http: request body too large");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
