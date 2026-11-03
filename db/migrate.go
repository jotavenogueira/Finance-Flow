package db

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
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
	var applied bool
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version = 1)").Scan(&applied); err != nil {
		return err
	}
	if !applied {
		script, err := migrations.ReadFile("migrations/001_users_categories.sql")
		if err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, string(script)); err != nil {
			return fmt.Errorf("aplicar migração 1: %w", err)
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO schema_migrations(version) VALUES (1)"); err != nil {
			return err
		}
	}
	return tx.Commit()
}
