package com.financeflow.controller;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.User;
import com.financeflow.usecase.AuthResult;
import com.financeflow.usecase.AuthUsecase;
import com.financeflow.usecase.LoginInput;
import com.financeflow.usecase.RegisterInput;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthUsecase auth;
    private final SessionCookies cookies;

    public AuthController(AuthUsecase auth, SessionCookies cookies) {
        this.auth = auth;
        this.cookies = cookies;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<Map<String, User>> register(@RequestBody RegisterInput input, HttpServletRequest request,
                                                      HttpServletResponse response) {
        AuthResult result = auth.register(input);
        cookies.setSession(request, response, result.session());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("user", result.user()));
    }

    @PostMapping("/auth/login")
    public Map<String, User> login(@RequestBody LoginInput input, HttpServletRequest request,
                                   HttpServletResponse response) {
        AuthResult result = auth.login(input);
        cookies.setSession(request, response, result.session());
        return Map.of("user", result.user());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = SessionCookies.read(request, SessionCookies.SESSION_COOKIE);
        if (token == null) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        auth.logout(token);
        cookies.clearSession(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    public Map<String, User> me(@RequestAttribute(AuthInterceptor.AUTHENTICATED_USER) User user) {
        return Map.of("user", user);
    }
}
