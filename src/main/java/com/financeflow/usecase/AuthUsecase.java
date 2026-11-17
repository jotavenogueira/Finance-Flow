package com.financeflow.usecase;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.GoogleIdentity;
import com.financeflow.model.User;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthUsecase {

    static final Duration SESSION_DURATION = Duration.ofHours(24);

    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(10);

    private final AuthStore repository;

    public AuthUsecase(AuthStore repository) {
        this.repository = repository;
    }

    public AuthResult register(RegisterInput input) {
        String name = Text.trim(input.name());
        if (Text.characters(name) < 1 || Text.characters(name) > 100) {
            throw new DomainException(DomainError.INVALID_INPUT, "o nome deve ter entre 1 e 100 caracteres");
        }
        String email = Text.normalizeEmail(input.email()).orElseThrow(
                () -> new DomainException(DomainError.INVALID_INPUT, "informe um e-mail valido"));
        if (!validPasswordLength(input.password())) {
            throw new DomainException(DomainError.INVALID_INPUT, "a senha deve ter entre 8 e 72 bytes");
        }
        String hash = PASSWORDS.encode(input.password());
        User user = repository.createUser(new User(0, name, email, hash));
        Session session = createSession(user.id());
        return new AuthResult(user.withoutPassword(), session);
    }

    public AuthResult login(LoginInput input) {
        String email = Text.normalizeEmail(input.email()).orElse(null);
        if (email == null || !validPasswordLength(input.password())) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        User user = repository.getUserByEmail(email);
        // Contas criadas pelo Google não têm senha local.
        if (user.passwordHash() == null || user.passwordHash().isEmpty()
                || !PASSWORDS.matches(input.password(), user.passwordHash())) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        Session session = createSession(user.id());
        return new AuthResult(user.withoutPassword(), session);
    }

    public User authenticate(String token) {
        if (!Tokens.valid(token)) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        return repository.getUserBySession(Tokens.hash(token));
    }

    public void logout(String token) {
        if (!Tokens.valid(token)) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        repository.deleteSession(Tokens.hash(token));
    }

    public AuthResult googleLogin(GoogleIdentity identity) {
        GoogleIdentity normalized = normalizeGoogleIdentity(identity);
        User user = googleStore().resolveGoogleUser(normalized);
        Session session = createSession(user.id());
        return new AuthResult(user.withoutPassword(), session);
    }

    public void linkGoogle(int userId, GoogleIdentity identity) {
        if (userId < 1) {
            throw new DomainException(DomainError.INVALID_CREDENTIALS);
        }
        googleStore().linkGoogleUser(userId, normalizeGoogleIdentity(identity));
    }

    private GoogleAuthStore googleStore() {
        if (repository instanceof GoogleAuthStore store) {
            return store;
        }
        throw new IllegalStateException("Google identity storage is unavailable");
    }

    private Session createSession(int userId) {
        Session session = new Session(Tokens.newToken(), Instant.now().plus(SESSION_DURATION));
        repository.createSession(Tokens.hash(session.token()), userId, session.expiresAt());
        return session;
    }

    private static boolean validPasswordLength(String password) {
        // O BCrypt considera somente os primeiros 72 bytes da senha.
        int length = Text.bytes(password);
        return length >= 8 && length <= 72;
    }

    static GoogleIdentity normalizeGoogleIdentity(GoogleIdentity identity) {
        String subject = identity.subject();
        if (subject == null || subject.isEmpty() || subject.length() > 255 || !subject.strip().equals(subject)) {
            throw new DomainException(DomainError.INVALID_INPUT, "identificador Google inválido");
        }
        String email = Text.normalizeEmail(identity.email()).orElseThrow(
                () -> new DomainException(DomainError.INVALID_INPUT, "e-mail Google inválido"));
        String name = Text.trim(identity.name());
        if (name.isEmpty()) {
            name = email.substring(0, email.indexOf('@'));
        }
        if (Text.characters(name) > 100) {
            name = name.substring(0, name.offsetByCodePoints(0, 100));
        }
        return new GoogleIdentity(subject, email, name);
    }
}
