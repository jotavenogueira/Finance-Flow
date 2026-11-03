package usecase

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/mail"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jotavenogueira/Finance-Flow/model"
	"golang.org/x/crypto/bcrypt"
)

const sessionDuration = 24 * time.Hour

type RegisterInput struct {
	Name     string `json:"name"`
	Email    string `json:"email"`
	Password string `json:"password"`
}

type LoginInput struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

// Session.Token so e entregue ao cookie: o banco recebe seu hash.
type Session struct {
	Token     string
	ExpiresAt time.Time
}

type AuthStore interface {
	CreateUser(context.Context, model.User) (model.User, error)
	GetUserByEmail(context.Context, string) (model.User, error)
	CreateSession(context.Context, string, int, time.Time) error
	GetUserBySession(context.Context, string) (model.User, error)
	DeleteSession(context.Context, string) error
}

type AuthUsecase struct {
	repository AuthStore
}

func NewAuthUsecase(repository AuthStore) AuthUsecase {
	return AuthUsecase{repository: repository}
}

func (u AuthUsecase) Register(ctx context.Context, input RegisterInput) (model.User, Session, error) {
	name := strings.TrimSpace(input.Name)
	if utf8.RuneCountInString(name) < 1 || utf8.RuneCountInString(name) > 100 {
		return model.User{}, Session{}, fmt.Errorf("%w: o nome deve ter entre 1 e 100 caracteres", model.ErrInvalidInput)
	}
	email, valid := normalizeEmail(input.Email)
	if !valid {
		return model.User{}, Session{}, fmt.Errorf("%w: informe um e-mail valido", model.ErrInvalidInput)
	}
	if len(input.Password) < 8 || len(input.Password) > 72 {
		return model.User{}, Session{}, fmt.Errorf("%w: a senha deve ter entre 8 e 72 bytes", model.ErrInvalidInput)
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(input.Password), bcrypt.DefaultCost)
	if err != nil {
		return model.User{}, Session{}, fmt.Errorf("hash password: %w", err)
	}
	user, err := u.repository.CreateUser(ctx, model.User{Name: name, Email: email, PasswordHash: string(hash)})
	if err != nil {
		return model.User{}, Session{}, err
	}
	session, err := u.createSession(ctx, user.ID)
	if err != nil {
		return model.User{}, Session{}, err
	}
	user.PasswordHash = ""
	return user, session, nil
}

func (u AuthUsecase) Login(ctx context.Context, input LoginInput) (model.User, Session, error) {
	email, valid := normalizeEmail(input.Email)
	if !valid || len(input.Password) < 8 || len(input.Password) > 72 {
		return model.User{}, Session{}, model.ErrInvalidCredentials
	}
	user, err := u.repository.GetUserByEmail(ctx, email)
	if err != nil {
		if errors.Is(err, model.ErrInvalidCredentials) {
			return model.User{}, Session{}, model.ErrInvalidCredentials
		}
		return model.User{}, Session{}, err
	}
	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(input.Password)); err != nil {
		return model.User{}, Session{}, model.ErrInvalidCredentials
	}
	session, err := u.createSession(ctx, user.ID)
	if err != nil {
		return model.User{}, Session{}, err
	}
	user.PasswordHash = ""
	return user, session, nil
}

func (u AuthUsecase) Authenticate(ctx context.Context, token string) (model.User, error) {
	if !validSessionToken(token) {
		return model.User{}, model.ErrInvalidCredentials
	}
	return u.repository.GetUserBySession(ctx, hashSessionToken(token))
}

func (u AuthUsecase) Logout(ctx context.Context, token string) error {
	if !validSessionToken(token) {
		return model.ErrInvalidCredentials
	}
	return u.repository.DeleteSession(ctx, hashSessionToken(token))
}

func (u AuthUsecase) createSession(ctx context.Context, userID int) (Session, error) {
	randomBytes := make([]byte, 32)
	if _, err := rand.Read(randomBytes); err != nil {
		return Session{}, fmt.Errorf("generate session: %w", err)
	}
	session := Session{
		Token:     base64.RawURLEncoding.EncodeToString(randomBytes),
		ExpiresAt: time.Now().UTC().Add(sessionDuration),
	}
	if err := u.repository.CreateSession(ctx, hashSessionToken(session.Token), userID, session.ExpiresAt); err != nil {
		return Session{}, err
	}
	return session, nil
}

func normalizeEmail(input string) (string, bool) {
	email := strings.ToLower(strings.TrimSpace(input))
	if len(email) < 3 || len(email) > 254 {
		return "", false
	}
	address, err := mail.ParseAddress(email)
	return email, err == nil && address.Address == email
}

func validSessionToken(token string) bool {
	if len(token) != 43 {
		return false
	}
	decoded, err := base64.RawURLEncoding.DecodeString(token)
	return err == nil && len(decoded) == 32
}

func hashSessionToken(token string) string {
	hash := sha256.Sum256([]byte(token))
	return hex.EncodeToString(hash[:])
}
