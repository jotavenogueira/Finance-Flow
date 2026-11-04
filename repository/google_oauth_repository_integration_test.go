package repository_test

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/jotavenogueira/Finance-Flow/model"
	"github.com/jotavenogueira/Finance-Flow/repository"
)

func challengeHash(value string) string {
	hash := sha256.Sum256([]byte(value))
	return hex.EncodeToString(hash[:])
}

func testGoogleChallenge(name string) model.GoogleOAuthChallenge {
	return model.GoogleOAuthChallenge{
		StateHash: challengeHash(name + "-state"), BrowserHash: challengeHash(name + "-browser"),
		Nonce: name + "-nonce", Verifier: name + "-verifier", ExpiresAt: time.Now().UTC().Add(5 * time.Minute),
	}
}

func TestGoogleChallengesRequireMatchingBrowserAndAreSingleUse(t *testing.T) {
	connection := googleTestDatabase(t)
	store := repository.NewGoogleOAuthRepository(connection)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	challenge := testGoogleChallenge("single-use")
	if err := store.CreateGoogleChallenge(ctx, challenge); err != nil {
		t.Fatal(err)
	}
	for _, keys := range [][2]string{
		{challengeHash("wrong-state"), challenge.BrowserHash},
		{challenge.StateHash, challengeHash("wrong-browser")},
		{"", ""},
	} {
		if _, err := store.ConsumeGoogleChallenge(ctx, keys[0], keys[1]); !errors.Is(err, model.ErrInvalidCredentials) {
			t.Fatal("missing or mismatched state/browser must not consume a challenge")
		}
	}
	consumed, err := store.ConsumeGoogleChallenge(ctx, challenge.StateHash, challenge.BrowserHash)
	if err != nil {
		t.Fatal("the valid browser must still be able to consume its challenge:", err)
	}
	if consumed.Nonce != challenge.Nonce || consumed.Verifier != challenge.Verifier || consumed.LinkUserID != 0 || consumed.LinkSessionHash != "" {
		t.Fatal("a valid consume must return the stored nonce and PKCE verifier without a linked account")
	}
	if _, err := store.ConsumeGoogleChallenge(ctx, challenge.StateHash, challenge.BrowserHash); !errors.Is(err, model.ErrInvalidCredentials) {
		t.Fatal("an OAuth state must be unusable after its first successful consume")
	}
}

func TestGoogleChallengesExpireAndAreCleaned(t *testing.T) {
	connection := googleTestDatabase(t)
	store := repository.NewGoogleOAuthRepository(connection)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	expired := testGoogleChallenge("expired")
	expired.ExpiresAt = time.Now().UTC().Add(-time.Minute)
	if err := store.CreateGoogleChallenge(ctx, expired); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ConsumeGoogleChallenge(ctx, expired.StateHash, expired.BrowserHash); !errors.Is(err, model.ErrInvalidCredentials) {
		t.Fatal("expired OAuth attempts must not authenticate")
	}
	if err := store.CreateGoogleChallenge(ctx, testGoogleChallenge("fresh")); err != nil {
		t.Fatal(err)
	}
	var expiredCount int
	if err := connection.QueryRowContext(ctx, `SELECT COUNT(*) FROM google_oauth_challenges WHERE state_hash=$1`, expired.StateHash).Scan(&expiredCount); err != nil || expiredCount != 0 {
		t.Fatal("creating a new attempt must remove old expired challenges")
	}
}

func TestConcurrentGoogleChallengeCallbacksHaveOnlyOneWinner(t *testing.T) {
	connection := googleTestDatabase(t)
	store := repository.NewGoogleOAuthRepository(connection)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	challenge := testGoogleChallenge("concurrent-callback")
	if err := store.CreateGoogleChallenge(ctx, challenge); err != nil {
		t.Fatal(err)
	}
	const attempts = 12
	errorsByAttempt := make(chan error, attempts)
	var workers sync.WaitGroup
	for range attempts {
		workers.Go(func() {
			_, err := store.ConsumeGoogleChallenge(ctx, challenge.StateHash, challenge.BrowserHash)
			errorsByAttempt <- err
		})
	}
	workers.Wait()
	close(errorsByAttempt)
	winners := 0
	for err := range errorsByAttempt {
		if err == nil {
			winners++
		} else if !errors.Is(err, model.ErrInvalidCredentials) {
			t.Error("unexpected concurrent consume error:", err)
		}
	}
	if winners != 1 {
		t.Fatalf("one OAuth state must permit exactly one callback, got %d winners", winners)
	}
}

func TestGoogleLinkChallengesRequireOriginalLiveSessionAndOwner(t *testing.T) {
	connection := googleTestDatabase(t)
	auth := repository.NewAuthRepository(connection)
	store := repository.NewGoogleOAuthRepository(connection)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	userA, err := auth.CreateUser(ctx, model.User{Name: "A", Email: "a@example.com", PasswordHash: "existing-hash-a"})
	if err != nil {
		t.Fatal(err)
	}
	userB, err := auth.CreateUser(ctx, model.User{Name: "B", Email: "b@example.com", PasswordHash: "existing-hash-b"})
	if err != nil {
		t.Fatal(err)
	}
	newLinkedAttempt := func(name string) model.GoogleOAuthChallenge {
		t.Helper()
		challenge := testGoogleChallenge(name)
		challenge.LinkUserID, challenge.LinkSessionHash = userA.ID, challengeHash(name+"-session")
		if err := auth.CreateSession(ctx, challenge.LinkSessionHash, userA.ID, time.Now().UTC().Add(time.Hour)); err != nil {
			t.Fatal(err)
		}
		if err := store.CreateGoogleChallenge(ctx, challenge); err != nil {
			t.Fatal(err)
		}
		return challenge
	}
	t.Run("logout before callback", func(t *testing.T) {
		challenge := newLinkedAttempt("logout-before-callback")
		if err := auth.DeleteSession(ctx, challenge.LinkSessionHash); err != nil {
			t.Fatal(err)
		}
		if _, err := store.ConsumeGoogleChallenge(ctx, challenge.StateHash, challenge.BrowserHash); !errors.Is(err, model.ErrInvalidCredentials) {
			t.Fatal("logging out must cancel an in-progress Google link")
		}
		active, err := store.GoogleLinkSessionActive(ctx, challenge)
		if err != nil || active {
			t.Fatal("a deleted linking session must no longer be active")
		}
	})
	t.Run("logout during provider call", func(t *testing.T) {
		challenge := newLinkedAttempt("logout-after-consume")
		consumed, err := store.ConsumeGoogleChallenge(ctx, challenge.StateHash, challenge.BrowserHash)
		if err != nil || consumed.LinkUserID != userA.ID || consumed.LinkSessionHash != challenge.LinkSessionHash {
			t.Fatal("a live owner session must permit its linking challenge")
		}
		active, err := store.GoogleLinkSessionActive(ctx, consumed)
		if err != nil || !active {
			t.Fatal("the original link session must initially be active")
		}
		if err := auth.DeleteSession(ctx, challenge.LinkSessionHash); err != nil {
			t.Fatal(err)
		}
		active, err = store.GoogleLinkSessionActive(ctx, consumed)
		if err != nil || active {
			t.Fatal("a logout during Google's external response must invalidate the consumed link")
		}
	})
	t.Run("expired session", func(t *testing.T) {
		challenge := newLinkedAttempt("expired-link-session")
		if _, err := connection.ExecContext(ctx, `UPDATE sessions SET expires_at=NOW()-INTERVAL '1 minute' WHERE token_hash=$1`, challenge.LinkSessionHash); err != nil {
			t.Fatal(err)
		}
		if _, err := store.ConsumeGoogleChallenge(ctx, challenge.StateHash, challenge.BrowserHash); !errors.Is(err, model.ErrInvalidCredentials) {
			t.Fatal("an expired owner session must not permit linking")
		}
		active, err := store.GoogleLinkSessionActive(ctx, challenge)
		if err != nil || active {
			t.Fatal("expired link sessions must not be active")
		}
	})
	t.Run("wrong session owner", func(t *testing.T) {
		challenge := testGoogleChallenge("wrong-link-owner")
		challenge.LinkUserID, challenge.LinkSessionHash = userB.ID, challengeHash("session-belongs-to-a")
		if err := auth.CreateSession(ctx, challenge.LinkSessionHash, userA.ID, time.Now().UTC().Add(time.Hour)); err != nil {
			t.Fatal(err)
		}
		if err := store.CreateGoogleChallenge(ctx, challenge); err != nil {
			t.Fatal(err)
		}
		if _, err := store.ConsumeGoogleChallenge(ctx, challenge.StateHash, challenge.BrowserHash); !errors.Is(err, model.ErrInvalidCredentials) {
			t.Fatal("a valid session belonging to another user must not authorize linking")
		}
		active, err := store.GoogleLinkSessionActive(ctx, challenge)
		if err != nil || active {
			t.Fatal("link session checks must compare both token hash and owner")
		}
	})
}
