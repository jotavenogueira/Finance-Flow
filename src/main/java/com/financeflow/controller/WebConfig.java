package com.financeflow.controller;

import com.financeflow.usecase.GoogleOAuth;
import com.financeflow.usecase.GoogleOAuthConfig;
import com.financeflow.usecase.GoogleOAuthStore;
import com.financeflow.usecase.GoogleOpenIdProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(WebConfig.class);

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns(
                "/auth/me", "/auth/logout", "/auth/google/link/start",
                "/categories", "/transactions", "/transactions/**");
    }

    @Bean
    public static GoogleOAuth googleOAuth(Environment environment, GoogleOAuthStore store) {
        GoogleOAuthConfig config;
        try {
            config = GoogleOAuthConfig.fromEnvironment(environment::getProperty);
        } catch (IllegalArgumentException exception) {
            LOG.warn("FinanceFlow: login Google desativado; confira as três variáveis GOOGLE_*.");
            return GoogleOAuth.disabled();
        }
        if (!config.enabled()) {
            return GoogleOAuth.disabled();
        }
        return new GoogleOAuth(config, store, new GoogleOpenIdProvider(config));
    }
}
