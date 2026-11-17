package com.financeflow.usecase;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Function;

public record GoogleOAuthConfig(String clientId, String clientSecret, String redirectUrl) {

    public static final GoogleOAuthConfig DISABLED = new GoogleOAuthConfig("", "", "");

    public static GoogleOAuthConfig fromEnvironment(Function<String, String> getenv) {
        GoogleOAuthConfig config = new GoogleOAuthConfig(Text.trim(getenv.apply("GOOGLE_CLIENT_ID")),
                Text.trim(getenv.apply("GOOGLE_CLIENT_SECRET")), Text.trim(getenv.apply("GOOGLE_REDIRECT_URL")));
        if (config.clientId.isEmpty() && config.clientSecret.isEmpty() && config.redirectUrl.isEmpty()) {
            return config;
        }
        config.validate();
        return config;
    }

    public boolean enabled() {
        return !clientId.isEmpty() && !clientSecret.isEmpty() && !redirectUrl.isEmpty();
    }

    public void validate() {
        if (!enabled() || !validRedirect(redirectUrl)) {
            throw new IllegalArgumentException("configure GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET e GOOGLE_REDIRECT_URL "
                    + "com callback HTTPS (HTTP somente em localhost)");
        }
    }

    private static boolean validRedirect(String value) {
        URI redirect;
        try {
            redirect = new URI(value);
        } catch (URISyntaxException exception) {
            return false;
        }
        String host = redirect.getHost();
        boolean localhost = "localhost".equals(host) || "127.0.0.1".equals(host);
        return host != null && !host.isEmpty() && redirect.getRawUserInfo() == null
                && redirect.getRawQuery() == null && redirect.getRawFragment() == null
                && "/auth/google/callback".equals(redirect.getRawPath())
                && ("https".equals(redirect.getScheme()) || ("http".equals(redirect.getScheme()) && localhost));
    }

    /** Esquema e host (com porta) da URL de retorno, por exemplo https://exemplo.com. */
    public String canonicalOrigin() {
        URI redirect = URI.create(redirectUrl);
        return redirect.getScheme() + "://" + redirect.getRawAuthority();
    }
}
