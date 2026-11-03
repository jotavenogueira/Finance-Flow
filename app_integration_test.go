package main

import (
	"bytes"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jotavenogueira/Finance-Flow/db"
	"golang.org/x/crypto/bcrypt"
)

// O banco deve ser descartável e começar vazio. Nunca usa o banco do usuário.
func TestUserIsolationFlow(t *testing.T) {
	dsn := os.Getenv("FINANCEFLOW_TEST_DSN")
	if dsn == "" {
		t.Skip("defina FINANCEFLOW_TEST_DSN para um banco descartável financeflow_test_*")
	}
	parsed, err := url.Parse(dsn)
	if err != nil || !strings.HasPrefix(strings.TrimPrefix(parsed.Path, "/"), "financeflow_test_") {
		t.Fatal("o teste exige um banco descartável financeflow_test_*")
	}
	connection, err := sql.Open("postgres", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_, err = connection.Exec(`CREATE TABLE transaction (id SERIAL PRIMARY KEY, description VARCHAR(200) NOT NULL, amount NUMERIC(10,2) NOT NULL, type VARCHAR(15) NOT NULL, category VARCHAR(15) NOT NULL, date DATE NOT NULL);
	INSERT INTO transaction(description,amount,type,category,date) VALUES ('Registro legado de teste',10,'expense','food','2026-10-01')`)
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Migrate(connection); err != nil {
		t.Fatal(err)
	}
	if err := db.Migrate(connection); err != nil {
		t.Fatalf("migração deve ser idempotente: %v", err)
	}
	gin.SetMode(gin.TestMode)
	server := httptest.NewServer(NewHandler(connection))
	defer server.Close()
	newClient := func() *http.Client {
		jar, _ := cookiejar.New(nil)
		return &http.Client{Jar: jar, Timeout: 5 * time.Second}
	}
	a, b, anonymous := newClient(), newClient(), newClient()
	call := func(client *http.Client, method, path string, input any, status int) ([]byte, *http.Response) {
		t.Helper()
		var body io.Reader
		if input != nil {
			encoded, err := json.Marshal(input)
			if err != nil {
				t.Fatal(err)
			}
			body = bytes.NewReader(encoded)
		}
		req, err := http.NewRequest(method, server.URL+path, body)
		if err != nil {
			t.Fatal(err)
		}
		if input != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		res, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		data, err := io.ReadAll(res.Body)
		if err != nil {
			t.Fatal(err)
		}
		if res.StatusCode != status {
			t.Fatalf("%s %s: esperado %d, recebeu %d: %s", method, path, status, res.StatusCode, data)
		}
		return data, res
	}
	object := func(data []byte) map[string]any {
		t.Helper()
		var value map[string]any
		if err := json.Unmarshal(data, &value); err != nil {
			t.Fatal(err)
		}
		return value
	}
	for _, path := range []string{"/transactions", "/categories", "/auth/me"} {
		call(anonymous, "GET", path, nil, 401)
	}
	call(anonymous, "POST", "/auth/register", map[string]any{"name": "A", "email": "invalid", "password": "short"}, 400)
	password := "Local-test-password9!"
	register := func(client *http.Client, email string) map[string]any {
		t.Helper()
		data, response := call(client, "POST", "/auth/register", map[string]any{"name": "Pessoa Teste", "email": email, "password": password}, 201)
		if bytes.Contains(data, []byte("password")) {
			t.Fatal("resposta expôs informação de senha")
		}
		cookies := response.Cookies()
		if len(cookies) != 1 || !cookies[0].HttpOnly || cookies[0].SameSite != http.SameSiteStrictMode {
			t.Fatal("cookie de sessão sem proteção esperada")
		}
		return object(data)["user"].(map[string]any)
	}
	userA := register(a, " User-A@financeflow.test ")
	userB := register(b, "user-b@financeflow.test")
	call(anonymous, "POST", "/auth/register", map[string]any{"name": "Duplicado", "email": "USER-A@financeflow.test", "password": password}, 409)
	call(anonymous, "POST", "/auth/login", map[string]any{"email": "user-a@financeflow.test", "password": "incorrect-password"}, 401)
	call(a, "GET", "/auth/me", nil, 200)
	empty, _ := call(a, "GET", "/transactions", nil, 200)
	if string(empty) != "[]" {
		t.Fatalf("novo usuário recebeu registros legados: %s", empty)
	}
	catData, _ := call(a, "POST", "/categories", map[string]any{"name": "Academia"}, 201)
	categoryA := object(catData)["id"].(float64)
	otherCategories, _ := call(b, "GET", "/categories", nil, 200)
	if string(otherCategories) != "[]" {
		t.Fatal("categorias vazaram para outro usuário")
	}
	call(a, "POST", "/categories", map[string]any{"name": "academia"}, 409)
	call(b, "POST", "/categories", map[string]any{"name": "Academia"}, 201)
	input := map[string]any{"description": "Mensalidade", "amount": 99.90, "type": "expense", "category_id": categoryA, "date": "2026-10-28", "user_id": 999999}
	txData, _ := call(a, "POST", "/transactions", input, 201)
	transaction := object(txData)
	txID := int(transaction["id"].(float64))
	path := "/transactions/" + jsonNumber(txID)
	call(a, "GET", path, nil, 200)
	call(b, "GET", path, nil, 404)
	call(b, "DELETE", path, nil, 404)
	call(b, "POST", "/transactions", input, 400)
	otherList, _ := call(b, "GET", "/transactions", nil, 200)
	if string(otherList) != "[]" {
		t.Fatal("transações vazaram para outro usuário")
	}
	var owner int
	if err := connection.QueryRow("SELECT user_id FROM transaction WHERE id=$1", txID).Scan(&owner); err != nil || owner != int(userA["id"].(float64)) {
		t.Fatal("proprietário foi aceito do payload")
	}
	// A restrição do banco também impede categoria de outra pessoa.
	_, err = connection.Exec(`INSERT INTO transaction(description,amount,type,category,date,user_id,category_id) VALUES ('invalid',1,'expense','Academia','2026-10-28',$1,$2)`, int(userB["id"].(float64)), int(categoryA))
	if err == nil {
		t.Fatal("banco aceitou usuário/categoria incompatíveis")
	}
	// Requisições de escrita de outro site são recusadas, mesmo com sessão.
	req, _ := http.NewRequest("POST", server.URL+"/categories", strings.NewReader(`{"name":"Cross site"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", "https://another-site.invalid")
	res, err := a.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 403 {
		t.Fatal("operação cross-origin não foi bloqueada")
	}
	var passwordHash string
	if err := connection.QueryRow("SELECT password_hash FROM users WHERE id=$1", owner).Scan(&passwordHash); err != nil {
		t.Fatal(err)
	}
	if passwordHash == password || bcrypt.CompareHashAndPassword([]byte(passwordHash), []byte(password)) != nil {
		t.Fatal("senha não foi armazenada com bcrypt")
	}
	serverURL, _ := url.Parse(server.URL)
	token := a.Jar.Cookies(serverURL)[0].Value
	sum := sha256.Sum256([]byte(token))
	var sessionCount int
	if err := connection.QueryRow("SELECT count(*) FROM sessions WHERE token_hash=$1", hex.EncodeToString(sum[:])).Scan(&sessionCount); err != nil || sessionCount != 1 {
		t.Fatal("hash da sessão não encontrado")
	}
	call(a, "DELETE", path, nil, 204)
	call(a, "GET", path, nil, 404)
	call(a, "POST", "/auth/logout", nil, 204)
	call(a, "GET", "/auth/me", nil, 401)
	if err := connection.QueryRow("SELECT count(*) FROM sessions WHERE token_hash=$1", hex.EncodeToString(sum[:])).Scan(&sessionCount); err != nil || sessionCount != 0 {
		t.Fatal("logout não revogou sessão")
	}
	call(a, "POST", "/auth/login", map[string]any{"email": "user-a@financeflow.test", "password": password}, 200)
	call(a, "GET", "/categories", nil, 200)
	_, err = connection.Exec("UPDATE sessions SET expires_at=NOW()-INTERVAL '1 minute' WHERE user_id=$1", owner)
	if err != nil {
		t.Fatal(err)
	}
	call(a, "GET", "/auth/me", nil, 401)
	var legacyCount int
	if err := connection.QueryRow("SELECT count(*) FROM transaction WHERE user_id IS NULL AND description='Registro legado de teste'").Scan(&legacyCount); err != nil || legacyCount != 1 {
		t.Fatal("registro legado não foi preservado")
	}
}

func jsonNumber(value int) string { encoded, _ := json.Marshal(value); return string(encoded) }
