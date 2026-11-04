package repository_test

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"net/url"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jotavenogueira/Finance-Flow/db"
	"github.com/jotavenogueira/Finance-Flow/model"
	"github.com/jotavenogueira/Finance-Flow/repository"
)

func googleTestDatabase(t *testing.T) *sql.DB {
	t.Helper()
	dsn := os.Getenv("FINANCEFLOW_TEST_DSN")
	if dsn == "" {
		t.Skip("defina FINANCEFLOW_TEST_DSN para um banco descartável financeflow_test_*")
	}
	parsed, err := url.Parse(dsn)
	if err != nil || !strings.HasPrefix(strings.TrimPrefix(parsed.Path, "/"), "financeflow_test_") {
		t.Fatal("o teste exige um banco descartável financeflow_test_*")
	}
	admin, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal("could not open the disposable database")
	}
	var suffix [8]byte
	if _, err := rand.Read(suffix[:]); err != nil {
		t.Fatal(err)
	}
	// Generated lowercase hex identifiers cannot escape the owned test schema.
	schema := "financeflow_google_" + hex.EncodeToString(suffix[:])
	if _, err := admin.Exec(`CREATE SCHEMA ` + schema); err != nil {
		admin.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		defer admin.Close()
		if _, err := admin.Exec(`DROP SCHEMA ` + schema + ` CASCADE`); err != nil {
			t.Error("could not remove the generated test schema:", err)
		}
	})
	query := parsed.Query()
	query.Set("search_path", schema)
	parsed.RawQuery = query.Encode()
	connection, err := sql.Open("pgx", parsed.String())
	if err != nil {
		t.Fatal("could not open the isolated test schema")
	}
	connection.SetMaxOpenConns(5)
	t.Cleanup(func() { connection.Close() })
	if err := db.Migrate(connection); err != nil {
		t.Fatal(err)
	}
	if err := db.Migrate(connection); err != nil {
		t.Fatal("migrations must remain idempotent:", err)
	}
	return connection
}

func TestGoogleIdentityPersistenceAndExplicitLinking(t *testing.T) {
	connection := googleTestDatabase(t)
	store := repository.NewAuthRepository(connection)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	identity := model.GoogleIdentity{Subject: "google-new", Name: "Google User", Email: "google@example.com"}
	googleUser, err := store.ResolveGoogleUser(ctx, identity)
	if err != nil {
		t.Fatal(err)
	}
	var noPassword bool
	if err := connection.QueryRowContext(ctx, `SELECT password_hash IS NULL FROM users WHERE id=$1`, googleUser.ID).Scan(&noPassword); err != nil || !noPassword {
		t.Fatal("Google-only accounts must not have a fabricated local password")
	}
	identity.Email, identity.Name = "updated@example.com", "Updated Name"
	repeated, err := store.ResolveGoogleUser(ctx, identity)
	if err != nil || repeated.ID != googleUser.ID || repeated.Email != googleUser.Email || repeated.Name != googleUser.Name {
		t.Fatal("the stable Google subject must preserve the existing user and data owner")
	}
	localUser, err := store.CreateUser(ctx, model.User{Name: "Local User", Email: "local@example.com", PasswordHash: "preserved-password-hash"})
	if err != nil {
		t.Fatal(err)
	}
	localGoogle := model.GoogleIdentity{Subject: "google-local", Name: "Local User", Email: localUser.Email}
	if _, err := store.ResolveGoogleUser(ctx, localGoogle); !errors.Is(err, model.ErrGoogleAccountExists) {
		t.Fatal("a matching email must not automatically attach Google to an existing password account")
	}
	var count int
	if err := connection.QueryRowContext(ctx, `SELECT COUNT(*) FROM users`).Scan(&count); err != nil || count != 2 {
		t.Fatal("a refused email collision must not create orphan user records")
	}
	wrongEmail := localGoogle
	wrongEmail.Email = "different@example.com"
	if err := store.LinkGoogleUser(ctx, localUser.ID, wrongEmail); !errors.Is(err, model.ErrGoogleEmailMismatch) {
		t.Fatal("linking must require the same verified Google email as the authenticated account")
	}
	for range 2 {
		if err := store.LinkGoogleUser(ctx, localUser.ID, localGoogle); err != nil {
			t.Fatal("linking the same identity must succeed idempotently:", err)
		}
	}
	linked, err := store.ResolveGoogleUser(ctx, localGoogle)
	if err != nil || linked.ID != localUser.ID {
		t.Fatal("an explicitly linked Google account must sign in to the existing user")
	}
	persisted, err := store.GetUserByEmail(ctx, localUser.Email)
	if err != nil || persisted.PasswordHash != "preserved-password-hash" {
		t.Fatal("linking Google must preserve the user's existing password hash")
	}
	conflict := localGoogle
	conflict.Subject = "another-google-account"
	if err := store.LinkGoogleUser(ctx, localUser.ID, conflict); !errors.Is(err, model.ErrGoogleAccountConflict) {
		t.Fatal("an already linked account must not be overwritten by another Google subject")
	}
	conflict.Subject, conflict.Email = localGoogle.Subject, googleUser.Email
	if err := store.LinkGoogleUser(ctx, googleUser.ID, conflict); !errors.Is(err, model.ErrGoogleAccountConflict) {
		t.Fatal("another user must not claim an already owned Google subject")
	}
}

func TestConcurrentGoogleSignInsDoNotCreateDuplicateAccounts(t *testing.T) {
	connection := googleTestDatabase(t)
	store := repository.NewAuthRepository(connection)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	identity := model.GoogleIdentity{Subject: "google-concurrent", Name: "Concurrent User", Email: "concurrent@example.com"}
	const attempts = 12
	results := make(chan model.User, attempts)
	failures := make(chan error, attempts)
	var workers sync.WaitGroup
	for range attempts {
		workers.Go(func() {
			user, err := store.ResolveGoogleUser(ctx, identity)
			if err != nil {
				failures <- err
				return
			}
			results <- user
		})
	}
	workers.Wait()
	close(results)
	close(failures)
	for err := range failures {
		t.Error("concurrent Google sign-in failed:", err)
	}
	var owner int
	for user := range results {
		if owner == 0 {
			owner = user.ID
		}
		if user.ID != owner {
			t.Error("concurrent sign-ins resolved different owners")
		}
	}
	var users, identities int
	if err := connection.QueryRowContext(ctx, `SELECT (SELECT COUNT(*) FROM users), (SELECT COUNT(*) FROM google_accounts)`).Scan(&users, &identities); err != nil {
		t.Fatal(err)
	}
	if users != 1 || identities != 1 || owner == 0 {
		t.Fatal("concurrent sign-ins must persist exactly one user and one Google identity")
	}
}
