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
		SELECT id, name, email, password_hash FROM users WHERE email = $1`, email).
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
