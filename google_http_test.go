package main

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestGoogleHTTPBoundary(t *testing.T) {
	t.Setenv("GOOGLE_CLIENT_ID", "test-client")
	t.Setenv("GOOGLE_CLIENT_SECRET", "secret-not-for-the-browser")
	t.Setenv("GOOGLE_REDIRECT_URL", "https://finance.example/auth/google/callback")
	var logs bytes.Buffer
	previous := gin.DefaultWriter
	gin.DefaultWriter = &logs
	defer func() { gin.DefaultWriter = previous }()
	handler := NewHandler(nil)
	request := func(method, target string) *httptest.ResponseRecorder {
		t.Helper()
		result := httptest.NewRecorder()
		handler.ServeHTTP(result, httptest.NewRequest(method, target, nil))
		return result
	}
	providers := request(http.MethodGet, "https://finance.example/auth/providers")
	if providers.Code != 200 || providers.Body.String() != `{"google":true}` {
		t.Fatalf("provider availability: %d %s", providers.Code, providers.Body.String())
	}
	root := request(http.MethodGet, "https://old-alias.example/")
	if root.Code != http.StatusTemporaryRedirect || root.Header().Get("Location") != "https://finance.example/" {
		t.Fatalf("root must use configured canonical origin: %d", root.Code)
	}
	start := request(http.MethodPost, "https://old-alias.example/auth/google/start")
	if start.Code != http.StatusSeeOther || start.Header().Get("Location") != "https://finance.example/" {
		t.Fatalf("start on alias must redirect before creating state: %d", start.Code)
	}
	link := request(http.MethodPost, "https://finance.example/auth/google/link/start")
	if link.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous linking = %d", link.Code)
	}
	crossSite := httptest.NewRequest(http.MethodPost, "https://finance.example/auth/google/start", nil)
	crossSite.Header.Set("Sec-Fetch-Site", "cross-site")
	blocked := httptest.NewRecorder()
	handler.ServeHTTP(blocked, crossSite)
	if blocked.Code != http.StatusForbidden {
		t.Fatalf("cross-site OAuth start = %d", blocked.Code)
	}
	callback := request(http.MethodGet, "https://finance.example/auth/google/callback?state=private-state&code=private-code")
	if callback.Code != http.StatusSeeOther || callback.Header().Get("Referrer-Policy") != "no-referrer" {
		t.Fatal("invalid callback must redirect safely without referrer")
	}
	if strings.Contains(logs.String(), "private-code") || strings.Contains(logs.String(), "private-state") {
		t.Fatal("OAuth callback credentials entered request logs")
	}
	asset := request(http.MethodGet, "https://finance.example/static/google-g.png")
	if asset.Code != 200 || asset.Header().Get("Content-Type") != "image/png" {
		t.Fatal("Google brand image must be embedded")
	}
	if !strings.Contains(asset.Header().Get("Content-Security-Policy"), "form-action 'self' https://accounts.google.com") {
		t.Fatal("CSP must permit only the Google form redirect")
	}
	if strings.Contains(providers.Body.String(), "secret-not-for-the-browser") {
		t.Fatal("secret exposed")
	}
}

func TestGoogleDisabledKeepsPasswordAuthAvailable(t *testing.T) {
	t.Setenv("GOOGLE_CLIENT_ID", "")
	t.Setenv("GOOGLE_CLIENT_SECRET", "")
	t.Setenv("GOOGLE_REDIRECT_URL", "")
	handler := NewHandler(nil)
	for _, test := range []struct {
		path     string
		status   int
		contains string
	}{
		{"/", 200, "auth-form"},
		{"/auth/providers", 200, `{"google":false}`},
		{"/auth/me", 401, "message"},
	} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, test.path, nil))
		if response.Code != test.status || !strings.Contains(response.Body.String(), test.contains) {
			t.Fatalf("Google disabled: %s = %d", test.path, response.Code)
		}
	}
}
