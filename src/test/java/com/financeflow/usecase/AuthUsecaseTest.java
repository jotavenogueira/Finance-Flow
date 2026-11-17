package com.financeflow.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.GoogleIdentity;
import com.financeflow.model.User;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthUsecaseTest {

    private static final String PASSWORD = "SenhaDeTeste123!";

    static class GoogleAuthStoreStub extends MemoryAuthStore implements GoogleAuthStore {
        User user = new User(0, "", "", "");
        RuntimeException failure;
        GoogleIdentity identity;
        int userId;
        int calls;

        @Override
        public User resolveGoogleUser(GoogleIdentity identity) {
            calls++;
            this.identity = identity;
            if (failure != null) {
                throw failure;
            }
            return user;
        }

        @Override
        public void linkGoogleUser(int userId, GoogleIdentity identity) {
            calls++;
            this.userId = userId;
            this.identity = identity;
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static void assertError(DomainError expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.error()).isEqualTo(expected));
    }

    @Test
    void registerHashesPasswordAndPersistsOnlyHashedSession() {
        MemoryAuthStore store = new MemoryAuthStore();
        AuthUsecase service = new AuthUsecase(store);
        AuthResult result = service.register(new RegisterInput("  Ana Maria  ", " ANA@EXAMPLE.COM ", PASSWORD));

        assertThat(result.user().name()).isEqualTo("Ana Maria");
        assertThat(result.user().email()).isEqualTo("ana@example.com");
        assertThat(result.user().passwordHash()).isEmpty();
        String saved = store.users.get("ana@example.com").passwordHash();
        assertThat(saved).isNotEmpty().isNotEqualTo(PASSWORD);
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, saved)).isTrue();
        assertThat(store.sessions).doesNotContainKey(result.session().token());
        MemoryAuthStore.StoredSession persisted = store.sessions.get(Tokens.hash(result.session().token()));
        assertThat(persisted.userId()).isEqualTo(result.user().id());
        assertThat(Duration.between(Instant.now(), persisted.expiresAt())).isGreaterThan(Duration.ofHours(23));
        assertThat(service.authenticate(result.session().token()).id()).isEqualTo(result.user().id());
    }

    @Test
    void loginAndLogoutInvalidateTheSession() {
        MemoryAuthStore store = new MemoryAuthStore();
        AuthUsecase service = new AuthUsecase(store);
        service.register(new RegisterInput("Ana", "ana@example.com", PASSWORD));

        assertError(DomainError.INVALID_CREDENTIALS,
                () -> service.login(new LoginInput("ana@example.com", "SenhaErrada123!")));
        assertError(DomainError.INVALID_CREDENTIALS,
                () -> service.login(new LoginInput("missing@example.com", PASSWORD)));
        AuthResult result = service.login(new LoginInput(" ANA@EXAMPLE.COM ", PASSWORD));
        assertThat(result.user().id()).isPositive();
        assertThat(result.user().passwordHash()).isEmpty();
        service.logout(result.session().token());
        assertError(DomainError.INVALID_CREDENTIALS, () -> service.authenticate(result.session().token()));
    }

    @Test
    void expiredAndMalformedSessionsCannotAuthenticate() {
        MemoryAuthStore store = new MemoryAuthStore();
        AuthUsecase service = new AuthUsecase(store);
        AuthResult result = service.register(new RegisterInput("Ana", "ana@example.com", PASSWORD));
        String hash = Tokens.hash(result.session().token());
        store.sessions.put(hash, new MemoryAuthStore.StoredSession(result.user().id(),
                Instant.now().minus(Duration.ofMinutes(1))));
        for (String token : new String[] {result.session().token(), "", null, "forged-session", "a".repeat(10000)}) {
            assertError(DomainError.INVALID_CREDENTIALS, () -> service.authenticate(token));
        }
    }

    @Test
    void registerRejectsInvalidInputsBeforeCreatingRecords() {
        List<RegisterInput> inputs = List.of(
                new RegisterInput(" ", "ana@example.com", PASSWORD),
                new RegisterInput("á".repeat(101), "ana@example.com", PASSWORD),
                new RegisterInput("Ana", "Ana <ana@example.com>", PASSWORD),
                new RegisterInput("Ana", "missing-at", PASSWORD),
                new RegisterInput("Ana", "ana@example.com", "short"),
                new RegisterInput("Ana", "ana@example.com", "a".repeat(73)),
                new RegisterInput(null, null, null));
        for (RegisterInput input : inputs) {
            MemoryAuthStore store = new MemoryAuthStore();
            assertError(DomainError.INVALID_INPUT, () -> new AuthUsecase(store).register(input));
            assertThat(store.users).isEmpty();
            assertThat(store.sessions).isEmpty();
        }
    }

    @Test
    void googleLoginNormalizesVerifiedIdentityAndCreatesHashedSession() {
        GoogleAuthStoreStub store = new GoogleAuthStoreStub();
        store.user = new User(42, "Ana", "ana@example.com", "private");
        AuthResult result = new AuthUsecase(store).googleLogin(
                new GoogleIdentity("google-stable-subject", " ANA@EXAMPLE.COM ", "  Ana  "));

        assertThat(store.identity).isEqualTo(new GoogleIdentity("google-stable-subject", "ana@example.com", "Ana"));
        assertThat(result.user().id()).isEqualTo(42);
        assertThat(result.user().passwordHash()).isEmpty();
        assertThat(Tokens.valid(result.session().token())).isTrue();
        assertThat(store.sessions.get(Tokens.hash(result.session().token())).userId()).isEqualTo(42);
        assertThat(store.sessions).doesNotContainKey(result.session().token());
    }

    @Test
    void googleLoginDoesNotCreateSessionForIdentityConflict() {
        for (RuntimeException failure : List.of(new DomainException(DomainError.GOOGLE_ACCOUNT_EXISTS),
                new DomainException(DomainError.GOOGLE_ACCOUNT_CONFLICT), new IllegalStateException("database"))) {
            GoogleAuthStoreStub store = new GoogleAuthStoreStub();
            store.failure = failure;
            assertThatThrownBy(() -> new AuthUsecase(store).googleLogin(
                    new GoogleIdentity("google-stable-subject", "ana@example.com", "Ana"))).isSameAs(failure);
            assertThat(store.sessions).isEmpty();
        }
    }

    @Test
    void googleLoginRejectsMalformedIdentityBeforeStorage() {
        List<GoogleIdentity> identities = List.of(
                new GoogleIdentity("", "ana@example.com", ""),
                new GoogleIdentity(" padded-subject ", "ana@example.com", ""),
                new GoogleIdentity("s".repeat(256), "ana@example.com", ""),
                new GoogleIdentity("google-subject", "Ana <ana@example.com>", ""),
                new GoogleIdentity("google-subject", "invalid", ""));
        for (GoogleIdentity identity : identities) {
            GoogleAuthStoreStub store = new GoogleAuthStoreStub();
            assertError(DomainError.INVALID_INPUT, () -> new AuthUsecase(store).googleLogin(identity));
            assertThat(store.calls).isZero();
            assertThat(store.sessions).isEmpty();
        }
    }

    @Test
    void googleProfileNamesFitExistingUserSchema() {
        String[][] samples = {{"", "ana"}, {"á".repeat(101), "á".repeat(100)}};
        for (String[] sample : samples) {
            GoogleAuthStoreStub store = new GoogleAuthStoreStub();
            store.user = new User(42, "", "", "");
            new AuthUsecase(store).googleLogin(new GoogleIdentity("subject", "ana@example.com", sample[0]));
            assertThat(store.identity.name()).isEqualTo(sample[1]);
        }
    }

    @Test
    void linkGoogleUsesAuthenticatedOwnerAndPropagatesMismatch() {
        GoogleAuthStoreStub store = new GoogleAuthStoreStub();
        store.failure = new DomainException(DomainError.GOOGLE_EMAIL_MISMATCH);
        AuthUsecase service = new AuthUsecase(store);
        GoogleIdentity identity = new GoogleIdentity("google-subject", " ANA@EXAMPLE.COM ", "Ana");

        assertError(DomainError.INVALID_CREDENTIALS, () -> service.linkGoogle(0, identity));
        assertThat(store.calls).isZero();
        assertError(DomainError.GOOGLE_EMAIL_MISMATCH, () -> service.linkGoogle(42, identity));
        assertThat(store.userId).isEqualTo(42);
        assertThat(store.identity.email()).isEqualTo("ana@example.com");
        assertThat(store.sessions).isEmpty();
    }

    @Test
    void googleOnlyUserCannotLoginWithArbitraryPassword() {
        MemoryAuthStore store = new MemoryAuthStore();
        store.users.put("ana@example.com", new User(1, "Ana", "ana@example.com", ""));
        assertError(DomainError.INVALID_CREDENTIALS, () -> new AuthUsecase(store).login(
                new LoginInput("ana@example.com", "arbitrary-password")));
        assertThat(store.sessions).isEmpty();
    }
}
