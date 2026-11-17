package com.financeflow.repository;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.GoogleIdentity;
import com.financeflow.model.User;
import com.financeflow.usecase.AuthStore;
import com.financeflow.usecase.GoogleAuthStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class AuthRepository implements AuthStore, GoogleAuthStore {

    private static final RowMapper<User> PUBLIC_USER =
            (row, index) -> new User(row.getInt("id"), row.getString("name"), row.getString("email"), "");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public AuthRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public User createUser(User user) {
        try {
            return jdbc.sql("""
                    INSERT INTO users (name, email, password_hash) VALUES (?, ?, ?)
                    RETURNING id, name, email""")
                    .params(user.name(), user.email(), user.passwordHash())
                    .query((row, index) -> new User(row.getInt("id"), row.getString("name"),
                            row.getString("email"), user.passwordHash()))
                    .single();
        } catch (DuplicateKeyException exception) {
            throw new DomainException(DomainError.CONFLICT);
        }
    }

    @Override
    public User getUserByEmail(String email) {
        return jdbc.sql("SELECT id, name, email, COALESCE(password_hash, '') AS password_hash FROM users WHERE email = ?")
                .param(email)
                .query((row, index) -> new User(row.getInt("id"), row.getString("name"),
                        row.getString("email"), row.getString("password_hash")))
                .optional()
                .orElseThrow(() -> new DomainException(DomainError.INVALID_CREDENTIALS));
    }

    @Override
    public void createSession(String tokenHash, int userId, Instant expiresAt) {
        jdbc.sql("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)")
                .params(tokenHash, userId, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .update();
    }

    @Override
    public User getUserBySession(String tokenHash) {
        return jdbc.sql("""
                SELECT u.id, u.name, u.email
                FROM sessions s JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.expires_at > NOW()""")
                .param(tokenHash)
                .query(PUBLIC_USER)
                .optional()
                .orElseThrow(() -> new DomainException(DomainError.INVALID_CREDENTIALS));
    }

    @Override
    public void deleteSession(String tokenHash) {
        jdbc.sql("DELETE FROM sessions WHERE token_hash = ?").param(tokenHash).update();
    }

    /**
     * Cria uma conta somente depois que o Google verificou a identidade. Um e-mail
     * igual em uma conta existente exige vínculo explícito.
     */
    @Override
    public User resolveGoogleUser(GoogleIdentity identity) {
        Optional<User> existing = userByGoogleSubject(identity.subject());
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<User> created;
        try {
            created = transactions.execute(status -> {
                Optional<User> inserted = jdbc.sql("""
                        INSERT INTO users (name, email, password_hash) VALUES (?, ?, NULL)
                        ON CONFLICT (email) DO NOTHING RETURNING id, name, email""")
                        .params(identity.name(), identity.email())
                        .query(PUBLIC_USER)
                        .optional();
                inserted.ifPresent(user -> jdbc.sql("INSERT INTO google_accounts (subject, user_id) VALUES (?, ?)")
                        .params(identity.subject(), user.id())
                        .update());
                return inserted;
            });
        } catch (DuplicateKeyException exception) {
            return resolveGoogleCreationConflict(identity.subject(), DomainError.GOOGLE_ACCOUNT_CONFLICT);
        }
        if (created == null || created.isEmpty()) {
            // Um login simultâneo pode ter terminado de criar esta mesma identidade.
            // Consulta de novo, fora da transação, depois que a inserção concorrente terminou.
            return resolveGoogleCreationConflict(identity.subject(), DomainError.GOOGLE_ACCOUNT_EXISTS);
        }
        return created.get();
    }

    private Optional<User> userByGoogleSubject(String subject) {
        return jdbc.sql("""
                SELECT u.id, u.name, u.email FROM google_accounts g
                JOIN users u ON u.id = g.user_id WHERE g.subject = ?""")
                .param(subject)
                .query(PUBLIC_USER)
                .optional();
    }

    private User resolveGoogleCreationConflict(String subject, DomainError conflict) {
        return userByGoogleSubject(subject).orElseThrow(() -> new DomainException(conflict));
    }

    /**
     * Exige o ID do usuário autenticado e o e-mail verificado pelo Google. Nem um
     * e-mail igual nem um ID enviado pelo cliente equivalem a login.
     */
    @Override
    public void linkGoogleUser(int userId, GoogleIdentity identity) {
        transactions.executeWithoutResult(status -> {
            String email = jdbc.sql("SELECT email FROM users WHERE id = ? FOR UPDATE")
                    .param(userId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new DomainException(DomainError.INVALID_CREDENTIALS));
            if (!email.equals(identity.email())) {
                throw new DomainException(DomainError.GOOGLE_EMAIL_MISMATCH);
            }
            Optional<String> subject = jdbc.sql("SELECT subject FROM google_accounts WHERE user_id = ?")
                    .param(userId)
                    .query(String.class)
                    .optional();
            if (subject.isPresent()) {
                if (!subject.get().equals(identity.subject())) {
                    throw new DomainException(DomainError.GOOGLE_ACCOUNT_CONFLICT);
                }
                return;
            }
            try {
                jdbc.sql("INSERT INTO google_accounts (subject, user_id) VALUES (?, ?)")
                        .params(identity.subject(), userId)
                        .update();
            } catch (DuplicateKeyException exception) {
                throw new DomainException(DomainError.GOOGLE_ACCOUNT_CONFLICT);
            }
        });
    }
}
