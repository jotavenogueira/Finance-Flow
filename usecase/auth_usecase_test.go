package usecase

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/jotavenogueira/Finance-Flow/model"
	"golang.org/x/crypto/bcrypt"
)

type storedSession struct {
	userID    int
	expiresAt time.Time
}

type memoryAuthStore struct {
	users    map[string]model.User
	sessions map[string]storedSession
}

func newMemoryAuthStore() *memoryAuthStore {
	return &memoryAuthStore{users: make(map[string]model.User), sessions: make(map[string]storedSession)}
}

func (s *memoryAuthStore) CreateUser(_ context.Context, user model.User) (model.User, error) {
	if _, exists := s.users[user.Email]; exists {
		return model.User{}, model.ErrConflict
	}
	user.ID = len(s.users) + 1
	s.users[user.Email] = user
	return user, nil
}

func (s *memoryAuthStore) GetUserByEmail(_ context.Context, email string) (model.User, error) {
	user, exists := s.users[email]
	if !exists {
		return model.User{}, model.ErrInvalidCredentials
	}
	return user, nil
}

func (s *memoryAuthStore) CreateSession(_ context.Context, tokenHash string, userID int, expiresAt time.Time) error {
	s.sessions[tokenHash] = storedSession{userID: userID, expiresAt: expiresAt}
	return nil
}

func (s *memoryAuthStore) GetUserBySession(_ context.Context, tokenHash string) (model.User, error) {
	session, exists := s.sessions[tokenHash]
	if !exists || !session.expiresAt.After(time.Now()) {
		return model.User{}, model.ErrInvalidCredentials
	}
	for _, user := range s.users {
		if user.ID == session.userID {
			user.PasswordHash = ""
			return user, nil
		}
	}
	return model.User{}, model.ErrInvalidCredentials
}

func (s *memoryAuthStore) DeleteSession(_ context.Context, tokenHash string) error {
	delete(s.sessions, tokenHash)
	return nil
}

func TestRegisterHashesPasswordAndPersistsOnlyHashedSession(t *testing.T) {
	store := newMemoryAuthStore()
	service := NewAuthUsecase(store)
	password := "SenhaDeTeste123!"
	user, session, err := service.Register(context.Background(), RegisterInput{
		Name: "  Ana Maria  ", Email: " ANA@EXAMPLE.COM ", Password: password,
	})
	if err != nil {
		t.Fatal(err)
	}
	if user.Name != "Ana Maria" || user.Email != "ana@example.com" || user.PasswordHash != "" {
		t.Fatal("registration should normalize identity and omit the password hash from its result")
	}
	saved := store.users[user.Email]
	if saved.PasswordHash == password || saved.PasswordHash == "" {
		t.Fatal("the persisted password must be a hash")
	}
	if err := bcrypt.CompareHashAndPassword([]byte(saved.PasswordHash), []byte(password)); err != nil {
		t.Fatal("the password hash must verify the original password")
	}
	if _, found := store.sessions[session.Token]; found {
		t.Fatal("raw session tokens must not be persisted")
	}
	persisted, found := store.sessions[hashSessionToken(session.Token)]
	if !found || persisted.userID != user.ID || time.Until(persisted.expiresAt) < 23*time.Hour {
		t.Fatal("registration must persist an expiring session belonging to the new user")
	}
	if authenticated, err := service.Authenticate(context.Background(), session.Token); err != nil || authenticated.ID != user.ID {
		t.Fatal("the session should authenticate its owner")
	}
}

func TestLoginAndLogoutInvalidateTheSession(t *testing.T) {
	store := newMemoryAuthStore()
	service := NewAuthUsecase(store)
	ctx := context.Background()
	input := RegisterInput{Name: "Ana", Email: "ana@example.com", Password: "SenhaDeTeste123!"}
	_, _, err := service.Register(ctx, input)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := service.Login(ctx, LoginInput{Email: input.Email, Password: "SenhaErrada123!"}); !errors.Is(err, model.ErrInvalidCredentials) {
		t.Fatal("wrong passwords must be rejected with the same credentials error")
	}
	if _, _, err := service.Login(ctx, LoginInput{Email: "missing@example.com", Password: input.Password}); !errors.Is(err, model.ErrInvalidCredentials) {
		t.Fatal("unknown accounts must use the same credentials error")
	}
	user, session, err := service.Login(ctx, LoginInput{Email: " ANA@EXAMPLE.COM ", Password: input.Password})
	if err != nil || user.ID == 0 || user.PasswordHash != "" {
		t.Fatal("valid login must return a public user and session")
	}
	if err := service.Logout(ctx, session.Token); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Authenticate(ctx, session.Token); !errors.Is(err, model.ErrInvalidCredentials) {
		t.Fatal("logged-out sessions must no longer authenticate")
	}
}

func TestExpiredAndMalformedSessionsCannotAuthenticate(t *testing.T) {
	store := newMemoryAuthStore()
	service := NewAuthUsecase(store)
	ctx := context.Background()
	_, session, err := service.Register(ctx, RegisterInput{Name: "Ana", Email: "ana@example.com", Password: "SenhaDeTeste123!"})
	if err != nil {
		t.Fatal(err)
	}
	stored := store.sessions[hashSessionToken(session.Token)]
	stored.expiresAt = time.Now().Add(-time.Minute)
	store.sessions[hashSessionToken(session.Token)] = stored
	for _, token := range []string{session.Token, "", "forged-session", strings.Repeat("a", 10000)} {
		if _, err := service.Authenticate(ctx, token); !errors.Is(err, model.ErrInvalidCredentials) {
			t.Fatal("expired or malformed sessions must be rejected")
		}
	}
}

func TestRegisterRejectsInvalidInputsBeforeCreatingRecords(t *testing.T) {
	inputs := []RegisterInput{
		{Name: " ", Email: "ana@example.com", Password: "SenhaDeTeste123!"},
		{Name: strings.Repeat("á", 101), Email: "ana@example.com", Password: "SenhaDeTeste123!"},
		{Name: "Ana", Email: "Ana <ana@example.com>", Password: "SenhaDeTeste123!"},
		{Name: "Ana", Email: "missing-at", Password: "SenhaDeTeste123!"},
		{Name: "Ana", Email: "ana@example.com", Password: "short"},
		{Name: "Ana", Email: "ana@example.com", Password: strings.Repeat("a", 73)},
	}
	for _, input := range inputs {
		store := newMemoryAuthStore()
		if _, _, err := NewAuthUsecase(store).Register(context.Background(), input); !errors.Is(err, model.ErrInvalidInput) {
			t.Fatal("invalid registration inputs must return a validation error")
		}
		if len(store.users) != 0 || len(store.sessions) != 0 {
			t.Fatal("invalid registration must not create users or sessions")
		}
	}
}
