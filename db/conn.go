package db

import (
	"database/sql"
	"fmt"
	"net"
	"net/url"
	"os"

	_ "github.com/lib/pq"
)

func ConnectDB() (*sql.DB, error) {
	requiredVariables := []string{
		"DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_NAME", "DB_SSLMODE",
	}

	for _, name := range requiredVariables {
		if os.Getenv(name) == "" {
			return nil, fmt.Errorf("variavel de ambiente obrigatoria nao definida: %s", name)
		}
	}

	connectionURL := url.URL{
		Scheme: "postgres",
		User:   url.UserPassword(os.Getenv("DB_USER"), os.Getenv("DB_PASSWORD")),
		Host:   net.JoinHostPort(os.Getenv("DB_HOST"), os.Getenv("DB_PORT")),
		Path:   os.Getenv("DB_NAME"),
	}

	parameters := url.Values{}
	parameters.Set("sslmode", os.Getenv("DB_SSLMODE"))
	parameters.Set("connect_timeout", "5")
	connectionURL.RawQuery = parameters.Encode()

	connection, err := sql.Open("postgres", connectionURL.String())
	if err != nil {
		return nil, fmt.Errorf("abrir conexao com o PostgreSQL: %w", err)
	}

	if err := connection.Ping(); err != nil {
		connection.Close()
		return nil, fmt.Errorf("conectar ao PostgreSQL: %w", err)
	}

	fmt.Println("Successfully connected to the database!")
	return connection, nil
}
