package com.financeflow.controller;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.User;
import com.financeflow.usecase.AuthUsecase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Exige uma sessão válida; o usuário autenticado fica disponível como atributo. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTHENTICATED_USER = "financeflow.authenticated_user";

    private final AuthUsecase auth;

    public AuthInterceptor(AuthUsecase auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = SessionCookies.read(request, SessionCookies.SESSION_COOKIE);
        if (token == null) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        User user = auth.authenticate(token);
        request.setAttribute(AUTHENTICATED_USER, user);
        return true;
    }
}
