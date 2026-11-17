package com.financeflow.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.financeflow.TestDatabase;
import com.financeflow.db.DatabaseMigrator;
import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.GoogleIdentity;
import com.financeflow.model.GoogleOAuthChallenge;
import com.financeflow.model.User;
import com.financeflow.usecase.Tokens;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class GoogleRepositoryIntegrationTest {

    TestDatabase database;
    AuthRepository auth;
    GoogleOAuthRepository challenges;
    JdbcClient sql;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open();
        DatabaseMigrator.migrate(database.dataSource);
        DatabaseMigrator.migrate(database.dataSource);
        auth = new AuthRepository(database.dataSource);
        challenges = new GoogleOAuthRepository(database.dataSource);
        sql = JdbcClient.create(database.dataSource);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    private static void assertError(DomainError expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                exception -> assertThat(exception.error()).isEqualTo(expected));
    }

    private static GoogleOAuthChallenge challenge(String name) {
        return new GoogleOAuthChallenge(Tokens.hash(name + "-state"), Tokens.hash(name + "-browser"),
                name + "-nonce", name + "-verifier", 0, "", Instant.now().plus(Duration.ofMinutes(10)));
    }

    private static GoogleOAuthChallenge linked(GoogleOAuthChallenge c, int userId, String sessionHash) {
        return new GoogleOAuthChallenge(c.stateHash(), c.browserHash(), c.nonce(), c.verifier(), userId, sessionHash,
                c.expiresAt());
    }

    private static <T> List<Future<T>> concurrently(int attempts, Callable<T> task) throws InterruptedException {
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                tasks.add(task);
            }
            return pool.invokeAll(tasks);
        }
    }

    @Test
    void googleIdentityPersistenceAndExplicitLinking() {
        User googleUser = auth.resolveGoogleUser(new GoogleIdentity("google-new", "google@example.com", "Google User"));
        assertThat(sql.sql("SELECT password_hash IS NULL FROM users WHERE id = ?").param(googleUser.id())
                .query(Boolean.class).single()).as("conta Google sem senha local").isTrue();
        User repeated = auth.resolveGoogleUser(new GoogleIdentity("google-new", "updated@example.com", "Updated Name"));
        assertThat(repeated).isEqualTo(googleUser);

        User localUser = auth.createUser(new User(0, "Local User", "local@example.com", "preserved-password-hash"));
        GoogleIdentity localGoogle = new GoogleIdentity("google-local", localUser.email(), "Local User");
        assertError(DomainError.GOOGLE_ACCOUNT_EXISTS, () -> auth.resolveGoogleUser(localGoogle));
        assertThat(sql.sql("SELECT COUNT(*) FROM users").query(Integer.class).single()).isEqualTo(2);

        assertError(DomainError.GOOGLE_EMAIL_MISMATCH, () -> auth.linkGoogleUser(localUser.id(),
                new GoogleIdentity("google-local", "different@example.com", "Local User")));
        auth.linkGoogleUser(localUser.id(), localGoogle);
        auth.linkGoogleUser(localUser.id(), localGoogle);
        assertThat(auth.resolveGoogleUser(localGoogle).id()).isEqualTo(localUser.id());
        assertThat(auth.getUserByEmail(localUser.email()).passwordHash()).isEqualTo("preserved-password-hash");

        assertError(DomainError.GOOGLE_ACCOUNT_CONFLICT, () -> auth.linkGoogleUser(localUser.id(),
                new GoogleIdentity("another-google-account", localUser.email(), "Local User")));
        assertError(DomainError.GOOGLE_ACCOUNT_CONFLICT, () -> auth.linkGoogleUser(googleUser.id(),
                new GoogleIdentity("google-local", googleUser.email(), "Google User")));
    }

    @Test
    void concurrentGoogleSignInsDoNotCreateDuplicateAccounts() throws Exception {
        GoogleIdentity identity = new GoogleIdentity("google-concurrent", "concurrent@example.com", "Concurrent User");
        List<Future<User>> results = concurrently(12, () -> auth.resolveGoogleUser(identity));
        int owner = results.get(0).get().id();
        for (Future<User> result : results) {
            assertThat(result.get().id()).isEqualTo(owner);
        }
        assertThat(sql.sql("SELECT (SELECT COUNT(*) FROM users) + (SELECT COUNT(*) FROM google_accounts)")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void challengesRequireMatchingBrowserAndAreSingleUse() {
        GoogleOAuthChallenge challenge = challenge("single-use");
        challenges.createGoogleChallenge(challenge);
        assertError(DomainError.INVALID_CREDENTIALS,
                () -> challenges.consumeGoogleChallenge(Tokens.hash("wrong-state"), challenge.browserHash()));
        assertError(DomainError.INVALID_CREDENTIALS,
                () -> challenges.consumeGoogleChallenge(challenge.stateHash(), Tokens.hash("wrong-browser")));
        assertError(DomainError.INVALID_CREDENTIALS, () -> challenges.consumeGoogleChallenge("", ""));
        GoogleOAuthChallenge consumed = challenges.consumeGoogleChallenge(challenge.stateHash(), challenge.browserHash());
        assertThat(consumed.nonce()).isEqualTo(challenge.nonce());
        assertThat(consumed.verifier()).isEqualTo(challenge.verifier());
        assertThat(consumed.linkUserId()).isZero();
        assertThat(consumed.linkSessionHash()).isEmpty();
        assertError(DomainError.INVALID_CREDENTIALS,
                () -> challenges.consumeGoogleChallenge(challenge.stateHash(), challenge.browserHash()));
    }

    @Test
    void challengesExpireAndAreCleaned() {
        GoogleOAuthChallenge fresh = challenge("expired");
        GoogleOAuthChallenge expired = new GoogleOAuthChallenge(fresh.stateHash(), fresh.browserHash(), fresh.nonce(),
                fresh.verifier(), 0, "", Instant.now().minus(Duration.ofMinutes(1)));
        challenges.createGoogleChallenge(expired);
        assertError(DomainError.INVALID_CREDENTIALS,
                () -> challenges.consumeGoogleChallenge(expired.stateHash(), expired.browserHash()));
        challenges.createGoogleChallenge(challenge("fresh"));
        assertThat(sql.sql("SELECT COUNT(*) FROM google_oauth_challenges WHERE state_hash = ?")
                .param(expired.stateHash()).query(Integer.class).single()).isZero();
    }

    @Test
    void concurrentChallengeCallbacksHaveOnlyOneWinner() throws Exception {
        GoogleOAuthChallenge challenge = challenge("concurrent-callback");
        challenges.createGoogleChallenge(challenge);
        List<Future<GoogleOAuthChallenge>> results = concurrently(12,
                () -> challenges.consumeGoogleChallenge(challenge.stateHash(), challenge.browserHash()));
        int winners = 0;
        for (Future<GoogleOAuthChallenge> result : results) {
            try {
                result.get();
                winners++;
            } catch (java.util.concurrent.ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(DomainException.class);
            }
        }
        assertThat(winners).isEqualTo(1);
    }

    @Test
    void linkChallengesRequireOriginalLiveSessionAndOwner() {
        User userA = auth.createUser(new User(0, "A", "a@example.com", "existing-hash-a"));
        User userB = auth.createUser(new User(0, "B", "b@example.com", "existing-hash-b"));
        Instant inOneHour = Instant.now().plus(Duration.ofHours(1));

        // Sair antes do callback.
        GoogleOAuthChallenge logout = linked(challenge("logout-before"), userA.id(), Tokens.hash("logout-before-session"));
        auth.createSession(logout.linkSessionHash(), userA.id(), inOneHour);
        challenges.createGoogleChallenge(logout);
        auth.deleteSession(logout.linkSessionHash());
        assertError(DomainError.INVALID_CREDENTIALS,
                () -> challenges.consumeGoogleChallenge(logout.stateHash(), logout.browserHash()));
        assertThat(challenges.googleLinkSessionActive(logout)).isFalse();

        // Sair durante a chamada ao Google.
        GoogleOAuthChallenge during = linked(challenge("logout-after"), userA.id(), Tokens.hash("logout-after-session"));
        auth.createSession(during.linkSessionHash(), userA.id(), inOneHour);
        challenges.createGoogleChallenge(during);
        GoogleOAuthChallenge consumed = challenges.consumeGoogleChallenge(during.stateHash(), during.browserHash());
        assertThat(consumed.linkUserId()).isEqualTo(userA.id());
        assertThat(consumed.linkSessionHash()).isEqualTo(during.linkSessionHash());
        assertThat(challenges.googleLinkSessionActive(consumed)).isTrue();
        auth.deleteSession(during.linkSessionHash());
        assertThat(challenges.googleLinkSessionActive(consumed)).isFalse();

        // Sessão expirada.
        GoogleOAuthChallenge expired = linked(challenge("expired-link"), userA.id(), Tokens.hash("expired-link-session"));
        auth.createSession(expired.linkSessionHash(), userA.id(), inOneHour);
        challenges.createGoogleChallenge(expired);
        sql.sql("UPDATE sessions SET expires_at = NOW() - INTERVAL '1 minute' WHERE token_hash = ?")
                .param(expired.linkSessionHash()).update();
        assertError(DomainError.INVALID_CREDENTIALS,
                () -> challenges.consumeGoogleChallenge(expired.stateHash(), expired.browserHash()));
        assertThat(challenges.googleLinkSessionActive(expired)).isFalse();

        // Sessão válida, mas de outro usuário.
        GoogleOAuthChallenge wrongOwner = linked(challenge("wrong-owner"), userB.id(), Tokens.hash("session-of-a"));
        auth.createSession(wrongOwner.linkSessionHash(), userA.id(), inOneHour);
        challenges.createGoogleChallenge(wrongOwner);
        assertError(DomainError.INVALID_CREDENTIALS,
                () -> challenges.consumeGoogleChallenge(wrongOwner.stateHash(), wrongOwner.browserHash()));
        assertThat(challenges.googleLinkSessionActive(wrongOwner)).isFalse();
    }
}
