package usecase

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/jotavenogueira/Finance-Flow/model"
)

type googleAuthStoreStub struct {
	*memoryAuthStore
	identity model.GoogleIdentity
	userID   int
	user     model.User
	err      error
	calls    int
}

func (s *googleAuthStoreStub) ResolveGoogleUser(_ context.Context, identity model.GoogleIdentity) (model.User, error) {
	s.calls++
	s.identity = identity
	return s.user, s.err
}

func (s *googleAuthStoreStub) LinkGoogleUser(_ context.Context, userID int, identity model.GoogleIdentity) error {
	s.calls++
	s.identity, s.userID = identity, userID
	return s.err
}

func TestGoogleLoginNormalizesVerifiedIdentityAndCreatesHashedSession(t *testing.T) {
	store := &googleAuthStoreStub{
		memoryAuthStore: newMemoryAuthStore(),
		user:            model.User{ID: 42, Name: "Ana", Email: "ana@example.com", PasswordHash: "private"},
	}
	user, session, err := NewAuthUsecase(store).GoogleLogin(context.Background(), model.GoogleIdentity{
		Subject: "google-stable-subject", Email: " ANA@EXAMPLE.COM ", Name: "  Ana  ",
	})
	if err != nil {
		t.Fatal(err)
	}
	if store.identity.Subject != "google-stable-subject" || store.identity.Email != "ana@example.com" || store.identity.Name != "Ana" {
		t.Fatalf("unexpected normalized Google identity: %+v", store.identity)
	}
	if user.ID != 42 || user.PasswordHash != "" || !validSessionToken(session.Token) {
		t.Fatal("Google login must return a public user and a fresh application session")
	}
	saved, ok := store.sessions[hashSessionToken(session.Token)]
	if !ok || saved.userID != 42 {
		t.Fatal("the application must persist the session hash for the Google account owner")
	}
	if _, rawSaved := store.sessions[session.Token]; rawSaved {
		t.Fatal("the raw session token must not be stored")
	}
}

func TestGoogleLoginDoesNotCreateSessionForIdentityConflict(t *testing.T) {
	for _, failure := range []error{model.ErrGoogleAccountExists, model.ErrGoogleAccountConflict, errors.New("database unavailable")} {
		store := &googleAuthStoreStub{memoryAuthStore: newMemoryAuthStore(), err: failure}
		_, session, err := NewAuthUsecase(store).GoogleLogin(context.Background(), model.GoogleIdentity{
			Subject: "google-stable-subject", Email: "ana@example.com", Name: "Ana",
		})
		if !errors.Is(err, failure) || session.Token != "" || len(store.sessions) != 0 {
			t.Fatal("failed identity resolution must preserve its error and create no login session")
		}
	}
}

func TestGoogleLoginRejectsMalformedIdentityBeforeStorage(t *testing.T) {
	identities := []model.GoogleIdentity{
		{Subject: "", Email: "ana@example.com"},
		{Subject: " padded-subject ", Email: "ana@example.com"},
		{Subject: strings.Repeat("s", 256), Email: "ana@example.com"},
		{Subject: "google-subject", Email: "Ana <ana@example.com>"},
		{Subject: "google-subject", Email: "invalid"},
	}
	for _, identity := range identities {
		store := &googleAuthStoreStub{memoryAuthStore: newMemoryAuthStore()}
		if _, _, err := NewAuthUsecase(store).GoogleLogin(context.Background(), identity); !errors.Is(err, model.ErrInvalidInput) {
			t.Fatal("invalid Google identity must be rejected")
		}
		if store.calls != 0 || len(store.sessions) != 0 {
			t.Fatal("invalid Google claims must not reach storage or create sessions")
		}
	}
}

func TestGoogleProfileNamesFitExistingUserSchema(t *testing.T) {
	for _, sample := range []struct {
		name string
		want string
	}{
		{name: "", want: "ana"},
		{name: strings.Repeat("á", 101), want: strings.Repeat("á", 100)},
	} {
		store := &googleAuthStoreStub{memoryAuthStore: newMemoryAuthStore(), user: model.User{ID: 42}}
		if _, _, err := NewAuthUsecase(store).GoogleLogin(context.Background(), model.GoogleIdentity{Subject: "subject", Email: "ana@example.com", Name: sample.name}); err != nil {
			t.Fatal(err)
		}
		if store.identity.Name != sample.want {
			t.Fatal("Google profile name must have a useful fallback and fit the existing 100-character column")
		}
	}
}

func TestLinkGoogleUsesAuthenticatedOwnerAndPropagatesMismatch(t *testing.T) {
	store := &googleAuthStoreStub{memoryAuthStore: newMemoryAuthStore(), err: model.ErrGoogleEmailMismatch}
	service := NewAuthUsecase(store)
	identity := model.GoogleIdentity{Subject: "google-subject", Email: " ANA@EXAMPLE.COM ", Name: "Ana"}
	if err := service.LinkGoogle(context.Background(), 0, identity); !errors.Is(err, model.ErrInvalidCredentials) || store.calls != 0 {
		t.Fatal("linking requires an authenticated owner before calling the store")
	}
	if err := service.LinkGoogle(context.Background(), 42, identity); !errors.Is(err, model.ErrGoogleEmailMismatch) {
		t.Fatal("an email mismatch must not be hidden")
	}
	if store.userID != 42 || store.identity.Email != "ana@example.com" || len(store.sessions) != 0 {
		t.Fatal("linking must use the authenticated owner and must not silently create a new session")
	}
}

func TestGoogleOnlyUserCannotLoginWithArbitraryPassword(t *testing.T) {
	store := newMemoryAuthStore()
	store.users["ana@example.com"] = model.User{ID: 1, Name: "Ana", Email: "ana@example.com"}
	if _, _, err := NewAuthUsecase(store).Login(context.Background(), LoginInput{Email: "ana@example.com", Password: "arbitrary-password"}); !errors.Is(err, model.ErrInvalidCredentials) {
		t.Fatal("a Google-only account has no local password and must reject password login")
	}
	if len(store.sessions) != 0 {
		t.Fatal("a failed password login must not create sessions")
	}
}
