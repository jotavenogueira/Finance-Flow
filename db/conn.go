package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"strings"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"
)

func ConnectDB() (*sql.DB, error) {
	dsn, err := buildDSN(os.Getenv)
	if err != nil {
		return nil, err
	}

	connection, err := sql.Open("pgx", dsn)
	if err != nil {
		// Driver/parser errors may contain connection-string values. Do not
		// include the original error or DSN in an error returned to callers.
		return nil, errors.New("abrir conexao com o PostgreSQL: configuracao de conexao invalida")
	}

	// One pool is shared by this server instance. The provider's pooler still
	// controls the total number of database connections across instances.
	connection.SetMaxOpenConns(5)
	connection.SetMaxIdleConns(2)
	connection.SetConnMaxIdleTime(5 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := connection.PingContext(ctx); err != nil {
		connection.Close()
		return nil, errors.New("conectar ao PostgreSQL: verifique a configuracao e a disponibilidade do banco")
	}

	fmt.Println("Successfully connected to the database!")
	return connection, nil
}

// buildDSN only prepares configuration, so it can be tested without a database
// or real environment variables. DATABASE_URL takes precedence over local DB_*.
func buildDSN(getenv func(string) string) (string, error) {
	if rawURL := strings.TrimSpace(getenv("DATABASE_URL")); rawURL != "" {
		return normalizeDatabaseURL(rawURL)
	}

	requiredVariables := []string{
		"DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_NAME", "DB_SSLMODE",
	}

	for _, name := range requiredVariables {
		if getenv(name) == "" {
			return "", fmt.Errorf("variavel de ambiente obrigatoria nao definida: %s", name)
		}
	}

	connectionURL := url.URL{
		Scheme: "postgres",
		User:   url.UserPassword(getenv("DB_USER"), getenv("DB_PASSWORD")),
		Host:   net.JoinHostPort(getenv("DB_HOST"), getenv("DB_PORT")),
		Path:   getenv("DB_NAME"),
	}

	parameters := url.Values{}
	// Keep the local mode (including disable) unchanged for Docker development.
	parameters.Set("sslmode", getenv("DB_SSLMODE"))
	parameters.Set("connect_timeout", "10")
	connectionURL.RawQuery = parameters.Encode()
	return connectionURL.String(), nil
}

func normalizeDatabaseURL(rawURL string) (string, error) {
	connectionURL, err := url.Parse(rawURL)
	if err != nil {
		// net/url errors may quote the entire URL, including its password.
		return "", errors.New("DATABASE_URL invalida: informe uma URL PostgreSQL valida")
	}
	if connectionURL.Scheme != "postgres" && connectionURL.Scheme != "postgresql" {
		return "", errors.New("DATABASE_URL invalida: o esquema deve ser postgres ou postgresql")
	}
	if strings.TrimSpace(connectionURL.Hostname()) == "" {
		return "", errors.New("DATABASE_URL invalida: host obrigatorio")
	}
	if connectionURL.User == nil || strings.TrimSpace(connectionURL.User.Username()) == "" {
		return "", errors.New("DATABASE_URL invalida: usuario obrigatorio")
	}
	if strings.TrimSpace(strings.TrimPrefix(connectionURL.Path, "/")) == "" {
		return "", errors.New("DATABASE_URL invalida: nome do banco obrigatorio")
	}
	if connectionURL.Fragment != "" {
		return "", errors.New("DATABASE_URL invalida: fragmentos nao sao permitidos; codifique caracteres especiais")
	}
	parameters, err := url.ParseQuery(connectionURL.RawQuery)
	if err != nil {
		return "", errors.New("DATABASE_URL invalida: parametros de conexao invalidos")
	}
	// pgx supports channel binding. Preserve the requested authentication policy
	// and reject unknown values rather than silently weakening it.
	if parameters.Has("channel_binding") {
		switch parameters.Get("channel_binding") {
		case "require", "prefer", "disable":
		default:
			return "", errors.New("DATABASE_URL: channel_binding deve ser require, prefer ou disable")
		}
	}
	// Neon recommends verify-full to verify the certificate and hostname, in
	// addition to any channel-binding policy supplied in the connection URL.
	switch parameters.Get("sslmode") {
	case "", "require":
		parameters.Set("sslmode", "verify-full")
	}
	if parameters.Get("connect_timeout") == "" {
		parameters.Set("connect_timeout", "10")
	}
	connectionURL.RawQuery = parameters.Encode()
	return connectionURL.String(), nil
}
