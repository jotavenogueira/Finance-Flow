package db

import (
	"net/url"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

func environment(values map[string]string) func(string) string {
	return func(name string) string { return values[name] }
}

func localConfiguration() map[string]string {
	return map[string]string{
		"DB_HOST": "127.0.0.1", "DB_PORT": "5433", "DB_USER": "local_user",
		"DB_PASSWORD": "synthetic-password", "DB_NAME": "local_db", "DB_SSLMODE": "disable",
	}
}

func parseTestDSN(t *testing.T, dsn string) *url.URL {
	t.Helper()
	parsed, err := url.Parse(dsn)
	if err != nil {
		t.Fatal("generated DSN is invalid")
	}
	return parsed
}

func TestBuildDSNDatabaseURLTakesPrecedence(t *testing.T) {
	values := localConfiguration()
	values["DATABASE_URL"] = "postgresql://cloud_user:cloud_password@db.example.test/cloud_db"
	dsn, err := buildDSN(environment(values))
	if err != nil {
		t.Fatal(err)
	}
	parsed := parseTestDSN(t, dsn)
	if parsed.Hostname() != "db.example.test" || parsed.User.Username() != "cloud_user" || parsed.Path != "/cloud_db" {
		t.Fatal("DATABASE_URL did not override local configuration")
	}
	// A complete URL also works when none of the six local variables exist.
	if _, err := buildDSN(environment(map[string]string{"DATABASE_URL": values["DATABASE_URL"]})); err != nil {
		t.Fatalf("URL unexpectedly required local variables: %v", err)
	}
}

func TestBuildDSNRejectsInvalidURLWithoutLeakingValues(t *testing.T) {
	const secret = "never-print-this-password"
	tests := []struct {
		name string
		url  string
	}{
		{"invalid scheme", "https://user:" + secret + "@db.example.test/database"},
		{"malformed escape", "postgres://user:" + secret + "%zz@db.example.test/database"},
		{"invalid port", "postgres://user:" + secret + "@db.example.test:invalid/database"},
		{"missing host", "postgres://user:" + secret + "@/database"},
		{"missing user", "postgres://db.example.test/database?password=" + secret},
		{"empty user", "postgres://:" + secret + "@db.example.test/database"},
		{"missing database", "postgres://user:" + secret + "@db.example.test"},
		{"empty database", "postgres://user:" + secret + "@db.example.test/"},
		{"fragment", "postgres://user:" + secret + "@db.example.test/database#unexpected"},
		{"malformed query", "postgres://user:" + secret + "@db.example.test/database?application_name=%zz"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			values := localConfiguration()
			values["DATABASE_URL"] = tt.url
			dsn, err := buildDSN(environment(values))
			if err == nil || dsn != "" {
				t.Fatal("invalid URL was accepted or fell back to local configuration")
			}
			if strings.Contains(err.Error(), secret) || strings.Contains(err.Error(), tt.url) || strings.Contains(err.Error(), "db.example.test") {
				t.Fatal("validation error leaked connection details")
			}
		})
	}
}

func TestBuildDSNLocalValuesAreEscapedAndTLSModePreserved(t *testing.T) {
	values := localConfiguration()
	values["DB_HOST"] = "::1"
	values["DB_USER"] = "user @:/?&%"
	values["DB_PASSWORD"] = "password @:/?&#%+='"
	values["DB_NAME"] = "database /?&#%"
	dsn, err := buildDSN(environment(values))
	if err != nil {
		t.Fatal(err)
	}
	parsed := parseTestDSN(t, dsn)
	password, _ := parsed.User.Password()
	if parsed.Hostname() != "::1" || parsed.Port() != "5433" || parsed.User.Username() != values["DB_USER"] || password != values["DB_PASSWORD"] || strings.TrimPrefix(parsed.Path, "/") != values["DB_NAME"] {
		t.Fatal("local values did not survive URL escaping")
	}
	if parsed.Query().Get("sslmode") != "disable" || parsed.Query().Get("connect_timeout") != "10" {
		t.Fatal("local TLS mode or timeout was not preserved")
	}
}

func TestBuildDSNRequiresEveryLocalVariableWithoutURL(t *testing.T) {
	for _, name := range []string{"DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_NAME", "DB_SSLMODE"} {
		t.Run(name, func(t *testing.T) {
			values := localConfiguration()
			delete(values, name)
			_, err := buildDSN(environment(values))
			if err == nil || !strings.Contains(err.Error(), name) {
				t.Fatal("missing local variable was not reported by name")
			}
		})
	}
}

func TestBuildDSNDatabaseURLTLSAndTimeout(t *testing.T) {
	for _, tt := range []struct {
		name, query, mode, timeout string
	}{
		{"defaults", "", "verify-full", "10"},
		{"require upgraded", "?sslmode=require", "verify-full", "10"},
		{"empty mode", "?sslmode=", "verify-full", "10"},
		{"verify-full preserved", "?sslmode=verify-full", "verify-full", "10"},
		{"verify-ca preserved", "?sslmode=verify-ca", "verify-ca", "10"},
		{"explicit local disable", "?sslmode=disable", "disable", "10"},
		{"custom timeout", "?connect_timeout=23", "verify-full", "23"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			dsn, err := buildDSN(environment(map[string]string{
				"DATABASE_URL": "postgres://user:password@db.example.test/database" + tt.query,
			}))
			if err != nil {
				t.Fatal(err)
			}
			parameters := parseTestDSN(t, dsn).Query()
			if parameters.Get("sslmode") != tt.mode || parameters.Get("connect_timeout") != tt.timeout {
				t.Fatal("unexpected TLS mode or connection timeout")
			}
		})
	}
}

func TestBuildDSNPreservesAdditionalURLParametersAndEscapes(t *testing.T) {
	input := &url.URL{
		Scheme: "postgresql", User: url.UserPassword("user@name", "password@:/?&#%"),
		Host: "db.example.test:5432", Path: "/database space",
	}
	parameters := url.Values{
		"sslmode": {"require"}, "connect_timeout": {"20"},
		"application_name": {"FinanceFlow dashboard"},
		"options":          {"-c statement_timeout=5000"},
		"custom_parameter": {"first", "second"},
	}
	input.RawQuery = parameters.Encode()
	dsn, err := buildDSN(environment(map[string]string{"DATABASE_URL": input.String()}))
	if err != nil {
		t.Fatal(err)
	}
	parsed := parseTestDSN(t, dsn)
	password, _ := parsed.User.Password()
	if parsed.User.Username() != "user@name" || password != "password@:/?&#%" || parsed.Path != input.Path {
		t.Fatal("escaped URL credentials or database were changed")
	}
	for _, key := range []string{"application_name", "options", "custom_parameter", "connect_timeout"} {
		if strings.Join(parsed.Query()[key], "\x00") != strings.Join(parameters[key], "\x00") {
			t.Fatalf("additional URL parameter %s was changed", key)
		}
	}
}

func TestBuildDSNPreservesChannelBindingPolicy(t *testing.T) {
	for _, policy := range []string{"require", "prefer", "disable"} {
		t.Run(policy, func(t *testing.T) {
			dsn, err := buildDSN(environment(map[string]string{
				"DATABASE_URL": "postgres://user:password@db.example.test/database?channel_binding=" + policy,
			}))
			if err != nil {
				t.Fatal(err)
			}
			if parseTestDSN(t, dsn).Query().Get("channel_binding") != policy {
				t.Fatal("channel binding policy was not preserved")
			}
			config, err := pgx.ParseConfig(dsn)
			if err != nil {
				t.Fatal("pgx could not parse generated configuration")
			}
			if config.ChannelBinding != policy || config.TLSConfig == nil || config.TLSConfig.InsecureSkipVerify {
				t.Fatal("pgx did not preserve channel binding with verified TLS")
			}
		})
	}
}

func TestBuildDSNRejectsInvalidChannelBindingPolicy(t *testing.T) {
	for _, policy := range []string{"", "unknown", "REQUIRE"} {
		dsn, err := buildDSN(environment(map[string]string{
			"DATABASE_URL": "postgres://user:password@db.example.test/database?channel_binding=" + policy,
		}))
		if err == nil || dsn != "" || !strings.Contains(err.Error(), "channel_binding") {
			t.Fatal("invalid channel binding policy was silently accepted")
		}
	}
}
