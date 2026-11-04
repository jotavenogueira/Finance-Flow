package repository

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/jotavenogueira/Finance-Flow/model"
)

type GoogleOAuthRepository struct{ connection *sql.DB }

func NewGoogleOAuthRepository(connection *sql.DB) GoogleOAuthRepository {
	return GoogleOAuthRepository{connection: connection}
}

func (r GoogleOAuthRepository) CreateGoogleChallenge(ctx context.Context, challenge model.GoogleOAuthChallenge) error {
	// Desafios expirados não são mais utilizáveis e não precisam se acumular.
	if _, err := r.connection.ExecContext(ctx, "DELETE FROM google_oauth_challenges WHERE expires_at <= NOW()"); err != nil {
		return fmt.Errorf("clean oauth challenges: %w", err)
	}
	var userID any
	var sessionHash any
	if challenge.LinkUserID > 0 {
		userID, sessionHash = challenge.LinkUserID, challenge.LinkSessionHash
	}
	_, err := r.connection.ExecContext(ctx, `
		INSERT INTO google_oauth_challenges
			(state_hash, browser_hash, nonce, pkce_verifier, link_user_id, link_session_hash, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		challenge.StateHash, challenge.BrowserHash, challenge.Nonce, challenge.Verifier,
		userID, sessionHash, challenge.ExpiresAt)
	if err != nil {
		return fmt.Errorf("create oauth challenge: %w", err)
	}
	return nil
}

func (r GoogleOAuthRepository) ConsumeGoogleChallenge(ctx context.Context, stateHash, browserHash string) (model.GoogleOAuthChallenge, error) {
	var challenge model.GoogleOAuthChallenge
	err := r.connection.QueryRowContext(ctx, `
		DELETE FROM google_oauth_challenges c
		WHERE c.state_hash = $1 AND c.browser_hash = $2 AND c.expires_at > NOW()
			AND (c.link_user_id IS NULL OR EXISTS (
				SELECT 1 FROM sessions s WHERE s.token_hash = c.link_session_hash
					AND s.user_id = c.link_user_id AND s.expires_at > NOW()
			))
		RETURNING state_hash, browser_hash, nonce, pkce_verifier,
			COALESCE(link_user_id, 0), COALESCE(link_session_hash, ''), expires_at`,
		stateHash, browserHash).Scan(&challenge.StateHash, &challenge.BrowserHash,
		&challenge.Nonce, &challenge.Verifier, &challenge.LinkUserID, &challenge.LinkSessionHash, &challenge.ExpiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return challenge, model.ErrInvalidCredentials
	}
	if err != nil {
		return challenge, fmt.Errorf("consume oauth challenge: %w", err)
	}
	return challenge, nil
}

// Revalida depois da chamada externa: sair da conta também cancela um vínculo em andamento.
func (r GoogleOAuthRepository) GoogleLinkSessionActive(ctx context.Context, challenge model.GoogleOAuthChallenge) (bool, error) {
	var active bool
	err := r.connection.QueryRowContext(ctx, `
		SELECT EXISTS(SELECT 1 FROM sessions WHERE token_hash = $1 AND user_id = $2 AND expires_at > NOW())`,
		challenge.LinkSessionHash, challenge.LinkUserID).Scan(&active)
	if err != nil {
		return false, fmt.Errorf("check oauth link session: %w", err)
	}
	return active, nil
}
