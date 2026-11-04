package db

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"strconv"
	"strings"
	"time"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Migrate executa cada versão uma única vez. A transação garante que uma falha
// não deixe o banco parcialmente alterado; o lock serializa inicializações.
func Migrate(connection *sql.DB) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	tx, err := connection.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("iniciar migração: %w", err)
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock(61437821)"); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`); err != nil {
		return err
	}
	// ReadDir returns filenames in lexical order; migration files use a
	// zero-padded numeric prefix so later versions run after earlier versions.
	entries, err := migrations.ReadDir("migrations")
	if err != nil {
		return err
	}
	seen := make(map[int]bool)
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		prefix, _, found := strings.Cut(entry.Name(), "_")
		version, parseErr := strconv.Atoi(prefix)
		if !found || parseErr != nil || version < 1 || seen[version] {
			return fmt.Errorf("nome de migração inválido ou repetido: %s", entry.Name())
		}
		seen[version] = true
		var applied bool
		if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version = $1)", version).Scan(&applied); err != nil {
			return err
		}
		if applied {
			continue
		}
		script, err := migrations.ReadFile("migrations/" + entry.Name())
		if err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, string(script)); err != nil {
			return fmt.Errorf("aplicar migração %d: %w", version, err)
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO schema_migrations(version) VALUES ($1)", version); err != nil {
			return err
		}
	}
	return tx.Commit()
}
