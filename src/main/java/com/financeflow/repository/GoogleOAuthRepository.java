package com.financeflow.repository;

import com.financeflow.model.DomainError;
import com.financeflow.model.DomainException;
import com.financeflow.model.GoogleOAuthChallenge;
import com.financeflow.usecase.GoogleOAuthStore;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GoogleOAuthRepository implements GoogleOAuthStore {

    private final JdbcClient jdbc;

    public GoogleOAuthRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public void createGoogleChallenge(GoogleOAuthChallenge challenge) {
        // Desafios expirados não são mais utilizáveis e não precisam se acumular.
        jdbc.sql("DELETE FROM google_oauth_challenges WHERE expires_at <= NOW()").update();
        boolean link = challenge.linkUserId() > 0;
        jdbc.sql("""
                INSERT INTO google_oauth_challenges
                    (state_hash, browser_hash, nonce, pkce_verifier, link_user_id, link_session_hash, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(challenge.stateHash(), challenge.browserHash(), challenge.nonce(), challenge.verifier(),
                        new SqlParameterValue(Types.INTEGER, link ? challenge.linkUserId() : null),
                        new SqlParameterValue(Types.CHAR, link ? challenge.linkSessionHash() : null),
                        OffsetDateTime.ofInstant(challenge.expiresAt(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public GoogleOAuthChallenge consumeGoogleChallenge(String stateHash, String browserHash) {
        return jdbc.sql("""
                DELETE FROM google_oauth_challenges c
                WHERE c.state_hash = ? AND c.browser_hash = ? AND c.expires_at > NOW()
                    AND (c.link_user_id IS NULL OR EXISTS (
                        SELECT 1 FROM sessions s WHERE s.token_hash = c.link_session_hash
                            AND s.user_id = c.link_user_id AND s.expires_at > NOW()
                    ))
                RETURNING state_hash, browser_hash, nonce, pkce_verifier,
                    COALESCE(link_user_id, 0) AS link_user_id,
                    COALESCE(link_session_hash, '') AS link_session_hash, expires_at""")
                .params(stateHash, browserHash)
                .query((row, index) -> new GoogleOAuthChallenge(row.getString("state_hash"),
                        row.getString("browser_hash"), row.getString("nonce"), row.getString("pkce_verifier"),
                        row.getInt("link_user_id"), row.getString("link_session_hash"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional()
                .orElseThrow(() -> new DomainException(DomainError.INVALID_CREDENTIALS));
    }

    /** Revalida depois da chamada externa: sair da conta também cancela um vínculo em andamento. */
    @Override
    public boolean googleLinkSessionActive(GoogleOAuthChallenge challenge) {
        Boolean active = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM sessions WHERE token_hash = ? AND user_id = ? AND expires_at > NOW())""")
                .params(challenge.linkSessionHash(), challenge.linkUserId())
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(active);
    }
}
