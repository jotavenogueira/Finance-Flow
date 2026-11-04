package repository

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/jotavenogueira/Finance-Flow/model"
)

type AuthRepository struct {
	connection *sql.DB
}

func NewAuthRepository(connection *sql.DB) AuthRepository {
	return AuthRepository{connection: connection}
}

func (r AuthRepository) CreateUser(ctx context.Context, user model.User) (model.User, error) {
	err := r.connection.QueryRowContext(ctx, `
		INSERT INTO users (name, email, password_hash) VALUES ($1, $2, $3)
		RETURNING id, name, email`, user.Name, user.Email, user.PasswordHash).
		Scan(&user.ID, &user.Name, &user.Email)
	if err != nil {
		var databaseError interface{ SQLState() string }
		if errors.As(err, &databaseError) && databaseError.SQLState() == "23505" {
			return model.User{}, model.ErrConflict
		}
		return model.User{}, fmt.Errorf("create user: %w", err)
	}
	return user, nil
}

func (r AuthRepository) GetUserByEmail(ctx context.Context, email string) (model.User, error) {
	var user model.User
	err := r.connection.QueryRowContext(ctx, `
		SELECT id, name, email, COALESCE(password_hash, '') FROM users WHERE email = $1`, email).
		Scan(&user.ID, &user.Name, &user.Email, &user.PasswordHash)
	if errors.Is(err, sql.ErrNoRows) {
		return model.User{}, model.ErrInvalidCredentials
	}
	if err != nil {
		return model.User{}, fmt.Errorf("find user: %w", err)
	}
	return user, nil
}

func (r AuthRepository) CreateSession(ctx context.Context, tokenHash string, userID int, expiresAt time.Time) error {
	_, err := r.connection.ExecContext(ctx, `
		INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ($1, $2, $3)`,
		tokenHash, userID, expiresAt)
	if err != nil {
		return fmt.Errorf("create session: %w", err)
	}
	return nil
}

func (r AuthRepository) GetUserBySession(ctx context.Context, tokenHash string) (model.User, error) {
	var user model.User
	err := r.connection.QueryRowContext(ctx, `
		SELECT u.id, u.name, u.email
		FROM sessions s JOIN users u ON u.id = s.user_id
		WHERE s.token_hash = $1 AND s.expires_at > NOW()`, tokenHash).
		Scan(&user.ID, &user.Name, &user.Email)
	if errors.Is(err, sql.ErrNoRows) {
		return model.User{}, model.ErrInvalidCredentials
	}
	if err != nil {
		return model.User{}, fmt.Errorf("find session: %w", err)
	}
	return user, nil
}

func (r AuthRepository) DeleteSession(ctx context.Context, tokenHash string) error {
	if _, err := r.connection.ExecContext(ctx, `DELETE FROM sessions WHERE token_hash = $1`, tokenHash); err != nil {
		return fmt.Errorf("delete session: %w", err)
	}
	return nil
}

// ResolveGoogleUser creates an account only after Google has verified its
// identity. A matching email on an existing account requires explicit linking.
func (r AuthRepository) ResolveGoogleUser(ctx context.Context, identity model.GoogleIdentity) (model.User, error) {
	user, err := r.getUserByGoogleSubject(ctx, identity.Subject)
	if err == nil {
		return user, nil
	}
	if !errors.Is(err, model.ErrNotFound) {
		return model.User{}, err
	}
	tx, err := r.connection.BeginTx(ctx, nil)
	if err != nil {
		return model.User{}, fmt.Errorf("begin Google account: %w", err)
	}
	defer tx.Rollback()
	err = tx.QueryRowContext(ctx, `
		INSERT INTO users (name, email, password_hash) VALUES ($1, $2, NULL)
		ON CONFLICT (email) DO NOTHING RETURNING id, name, email`, identity.Name, identity.Email).
		Scan(&user.ID, &user.Name, &user.Email)
	if errors.Is(err, sql.ErrNoRows) {
		// A concurrent sign-in may have finished creating this same identity.
		// Re-query outside the transaction after its conflicting insert completes.
		_ = tx.Rollback()
		return r.resolveGoogleCreationConflict(ctx, identity.Subject, model.ErrGoogleAccountExists)
	}
	if err != nil {
		return model.User{}, fmt.Errorf("create Google user: %w", err)
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO google_accounts (subject, user_id) VALUES ($1, $2)`, identity.Subject, user.ID); err != nil {
		if isUniqueViolation(err) {
			_ = tx.Rollback()
			return r.resolveGoogleCreationConflict(ctx, identity.Subject, model.ErrGoogleAccountConflict)
		}
		return model.User{}, fmt.Errorf("create Google identity: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return model.User{}, fmt.Errorf("commit Google account: %w", err)
	}
	return user, nil
}

func (r AuthRepository) getUserByGoogleSubject(ctx context.Context, subject string) (model.User, error) {
	var user model.User
	err := r.connection.QueryRowContext(ctx, `
		SELECT u.id, u.name, u.email FROM google_accounts g
		JOIN users u ON u.id = g.user_id WHERE g.subject = $1`, subject).
		Scan(&user.ID, &user.Name, &user.Email)
	if errors.Is(err, sql.ErrNoRows) {
		return model.User{}, model.ErrNotFound
	}
	if err != nil {
		return model.User{}, fmt.Errorf("find Google identity: %w", err)
	}
	return user, nil
}

func (r AuthRepository) resolveGoogleCreationConflict(ctx context.Context, subject string, conflict error) (model.User, error) {
	user, err := r.getUserByGoogleSubject(ctx, subject)
	if errors.Is(err, model.ErrNotFound) {
		return model.User{}, conflict
	}
	return user, err
}

// LinkGoogleUser requires the caller's authenticated user ID and the verified
// Google email. Neither a matching email nor a client-supplied user ID is login.
func (r AuthRepository) LinkGoogleUser(ctx context.Context, userID int, identity model.GoogleIdentity) error {
	tx, err := r.connection.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin Google link: %w", err)
	}
	defer tx.Rollback()
	var email string
	if err = tx.QueryRowContext(ctx, `SELECT email FROM users WHERE id = $1 FOR UPDATE`, userID).Scan(&email); errors.Is(err, sql.ErrNoRows) {
		return model.ErrInvalidCredentials
	} else if err != nil {
		return fmt.Errorf("find Google link owner: %w", err)
	}
	if email != identity.Email {
		return model.ErrGoogleEmailMismatch
	}
	var subject string
	err = tx.QueryRowContext(ctx, `SELECT subject FROM google_accounts WHERE user_id = $1`, userID).Scan(&subject)
	if err == nil {
		if subject != identity.Subject {
			return model.ErrGoogleAccountConflict
		}
		return tx.Commit()
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return fmt.Errorf("find linked Google account: %w", err)
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO google_accounts (subject, user_id) VALUES ($1, $2)`, identity.Subject, userID); err != nil {
		if isUniqueViolation(err) {
			return model.ErrGoogleAccountConflict
		}
		return fmt.Errorf("link Google account: %w", err)
	}
	return tx.Commit()
}

func isUniqueViolation(err error) bool {
	var databaseError interface{ SQLState() string }
	return errors.As(err, &databaseError) && databaseError.SQLState() == "23505"
}
