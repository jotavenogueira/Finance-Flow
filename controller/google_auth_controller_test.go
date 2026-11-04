package controller

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jotavenogueira/Finance-Flow/model"
	"github.com/jotavenogueira/Finance-Flow/usecase"
)

type controllerGoogleStore struct {
	challenge         model.GoogleOAuthChallenge
	consumed          bool
	creates, consumes int
	active            bool
}

func (s *controllerGoogleStore) CreateGoogleChallenge(_ context.Context, c model.GoogleOAuthChallenge) error {
	s.challenge = c
	s.creates++
	return nil
}
func (s *controllerGoogleStore) ConsumeGoogleChallenge(_ context.Context, state, browser string) (model.GoogleOAuthChallenge, error) {
	s.consumes++
	if s.consumed || s.challenge.StateHash != state || s.challenge.BrowserHash != browser || !s.challenge.ExpiresAt.After(time.Now()) {
		return model.GoogleOAuthChallenge{}, model.ErrInvalidCredentials
	}
	s.consumed = true
	return s.challenge, nil
}
func (s *controllerGoogleStore) GoogleLinkSessionActive(context.Context, model.GoogleOAuthChallenge) (bool, error) {
	return s.active, nil
}

type controllerGoogleProvider struct {
	state string
	calls int
	err   error
}

func (p *controllerGoogleProvider) AuthorizationURL(state, verifier, nonce string) string {
	p.state = state
	return "https://accounts.google.com/authorize?state=" + state
}
func (p *controllerGoogleProvider) Identity(context.Context, string, string, string) (model.GoogleIdentity, error) {
	p.calls++
	return model.GoogleIdentity{Subject: "stable-subject", Email: "ana@example.com", Name: "Ana"}, p.err
}

type controllerGoogleAuthStore struct {
	sessions   int
	links      int
	linkedUser int
	fail       error
	savedHash  string
}

func (*controllerGoogleAuthStore) CreateUser(context.Context, model.User) (model.User, error) {
	return model.User{}, errors.New("unused")
}
func (*controllerGoogleAuthStore) GetUserByEmail(context.Context, string) (model.User, error) {
	return model.User{}, errors.New("unused")
}
func (s *controllerGoogleAuthStore) CreateSession(_ context.Context, hash string, _ int, _ time.Time) error {
	s.sessions++
	s.savedHash = hash
	return nil
}
func (*controllerGoogleAuthStore) GetUserBySession(context.Context, string) (model.User, error) {
	return model.User{}, errors.New("unused")
}
func (*controllerGoogleAuthStore) DeleteSession(context.Context, string) error { return nil }
func (s *controllerGoogleAuthStore) ResolveGoogleUser(context.Context, model.GoogleIdentity) (model.User, error) {
	return model.User{ID: 7, Name: "Ana", Email: "ana@example.com"}, s.fail
}
func (s *controllerGoogleAuthStore) LinkGoogleUser(_ context.Context, id int, _ model.GoogleIdentity) error {
	s.links++
	s.linkedUser = id
	return s.fail
}

type controllerGoogleFixture struct {
	router   *gin.Engine
	store    *controllerGoogleStore
	provider *controllerGoogleProvider
	auth     *controllerGoogleAuthStore
}

func newControllerGoogleFixture(t *testing.T) *controllerGoogleFixture {
	t.Helper()
	t.Setenv("SESSION_COOKIE_SECURE", "true")
	gin.SetMode(gin.TestMode)
	f := &controllerGoogleFixture{store: &controllerGoogleStore{active: true}, provider: &controllerGoogleProvider{}, auth: &controllerGoogleAuthStore{}}
	flow := usecase.NewGoogleOAuth(usecase.GoogleOAuthConfig{ClientID: "test-client", ClientSecret: "test-secret", RedirectURL: "https://finance.example/auth/google/callback"}, f.store, f.provider)
	c := NewGoogleAuthController(usecase.NewAuthUsecase(f.auth), flow)
	f.router = gin.New()
	f.router.GET("/auth/providers", c.Providers)
	f.router.POST("/auth/google/start", c.Start)
	f.router.GET("/auth/google/callback", c.Callback)
	f.router.POST("/auth/google/link/start", func(c *gin.Context) { c.Set(authenticatedUserKey, model.User{ID: 7}); c.Next() }, c.LinkStart)
	return f
}
func (f *controllerGoogleFixture) request(method, path string, cookies ...*http.Cookie) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, "https://finance.example"+path, nil)
	for _, cookie := range cookies {
		req.AddCookie(cookie)
	}
	rec := httptest.NewRecorder()
	f.router.ServeHTTP(rec, req)
	return rec
}
func (f *controllerGoogleFixture) start(t *testing.T, link bool) *http.Cookie {
	t.Helper()
	path := "/auth/google/start"
	var cookies []*http.Cookie
	if link {
		path = "/auth/google/link/start"
		token, _ := usecase.NewGoogleBrowserToken()
		cookies = append(cookies, &http.Cookie{Name: sessionCookieName, Value: token})
	}
	rec := f.request("POST", path, cookies...)
	if rec.Code != http.StatusSeeOther || !strings.HasPrefix(rec.Header().Get("Location"), "https://accounts.google.com/") {
		t.Fatal("start should redirect to Google")
	}
	for _, cookie := range rec.Result().Cookies() {
		if cookie.Name == googleBrowserCookie {
			return cookie
		}
	}
	t.Fatal("start must create browser binding cookie")
	return nil
}
func (f *controllerGoogleFixture) callbackPath() string {
	return "/auth/google/callback?state=" + url.QueryEscape(f.provider.state) + "&code=test-code"
}
func assertGoogleRedirect(t *testing.T, rec *httptest.ResponseRecorder, location string) {
	t.Helper()
	if rec.Code != http.StatusSeeOther || rec.Header().Get("Location") != location {
		t.Fatalf("expected redirect %s, got %d %s", location, rec.Code, rec.Header().Get("Location"))
	}
}

func TestGoogleControllerCreatesLaxCookieAndAuthenticatesOnce(t *testing.T) {
	f := newControllerGoogleFixture(t)
	cookie := f.start(t, false)
	if !cookie.HttpOnly || !cookie.Secure || cookie.SameSite != http.SameSiteLaxMode || cookie.Path != "/auth/google" || cookie.MaxAge != 600 || cookie.Domain != "" {
		t.Fatal("browser binding needs secure host-only HttpOnly Lax cookie lasting ten minutes")
	}
	rec := f.request("GET", f.callbackPath(), cookie)
	assertGoogleRedirect(t, rec, "/")
	var session *http.Cookie
	for _, c := range rec.Result().Cookies() {
		if c.Name == sessionCookieName {
			session = c
		}
	}
	if session == nil || session.Value == "" || !session.HttpOnly || !session.Secure || session.SameSite != http.SameSiteStrictMode || f.auth.sessions != 1 || f.auth.savedHash == session.Value {
		t.Fatal("successful Google callback must create the existing secure hashed FinanceFlow session")
	}
	assertGoogleRedirect(t, f.request("GET", f.callbackPath(), cookie), "/?auth_error=google_expired")
	if f.provider.calls != 1 || f.auth.sessions != 1 {
		t.Fatal("replayed callback must not exchange or create another session")
	}
}

func TestGoogleControllerRejectsMissingMismatchedAndDuplicateCallbackBinding(t *testing.T) {
	for _, sample := range []string{"missing cookie", "mismatched cookie", "missing state", "duplicated state", "duplicated code"} {
		t.Run(sample, func(t *testing.T) {
			f := newControllerGoogleFixture(t)
			cookie := f.start(t, false)
			path := f.callbackPath()
			cookies := []*http.Cookie{cookie}
			switch sample {
			case "missing cookie":
				cookies = nil
			case "mismatched cookie":
				other, _ := usecase.NewGoogleBrowserToken()
				cookies = []*http.Cookie{{Name: googleBrowserCookie, Value: other}}
			case "missing state":
				path = "/auth/google/callback?code=test-code"
			case "duplicated state":
				path += "&state=other"
			case "duplicated code":
				path += "&code=other"
			}
			rec := f.request("GET", path, cookies...)
			assertGoogleRedirect(t, rec, "/?auth_error=google_expired")
			if f.provider.calls != 0 || f.auth.sessions != 0 {
				t.Fatal("unbound callback must not reach provider or authenticate")
			}
			cleared := false
			for _, c := range rec.Result().Cookies() {
				if c.Name == googleBrowserCookie && c.MaxAge < 0 {
					cleared = true
				}
			}
			if !cleared {
				t.Fatal("callback must clear temporary browser cookie even on failure")
			}
		})
	}
}

func TestGoogleControllerConsumesDenialAndHidesProviderErrors(t *testing.T) {
	f := newControllerGoogleFixture(t)
	cookie := f.start(t, false)
	path := "/auth/google/callback?state=" + url.QueryEscape(f.provider.state) + "&error=access_denied&error_description=private-provider-data"
	rec := f.request("GET", path, cookie)
	assertGoogleRedirect(t, rec, "/?auth_error=google_denied")
	if !f.store.consumed || f.provider.calls != 0 || strings.Contains(rec.Body.String(), "private-provider-data") {
		t.Fatal("denial must consume challenge without exchanging or reflecting provider details")
	}
	f = newControllerGoogleFixture(t)
	cookie = f.start(t, false)
	f.provider.err = errors.New("sensitive-provider-token")
	rec = f.request("GET", f.callbackPath(), cookie)
	assertGoogleRedirect(t, rec, "/?auth_error=google_failed")
	if f.auth.sessions != 0 || strings.Contains(rec.Body.String(), "sensitive-provider-token") {
		t.Fatal("exchange failure must not authenticate or leak provider details")
	}
}

func TestGoogleControllerRequiresActiveOriginalSessionBeforeLinking(t *testing.T) {
	for _, active := range []bool{true, false} {
		t.Run(map[bool]string{true: "active", false: "logged out"}[active], func(t *testing.T) {
			f := newControllerGoogleFixture(t)
			cookie := f.start(t, true)
			f.store.active = active
			// The Strict application cookie is intentionally absent on the cross-site callback.
			rec := f.request("GET", f.callbackPath(), cookie)
			if active {
				assertGoogleRedirect(t, rec, "/?auth_success=google_linked")
				if f.auth.links != 1 || f.auth.linkedUser != 7 {
					t.Fatal("link must use original authenticated owner")
				}
			} else {
				assertGoogleRedirect(t, rec, "/?auth_error=google_expired")
				if f.auth.links != 0 {
					t.Fatal("logging out must prevent pending link")
				}
			}
			if f.auth.sessions != 0 {
				t.Fatal("linking must preserve current login rather than create another session")
			}
		})
	}
}

func TestGoogleControllerReturnsExplicitConflictWithoutCreatingSession(t *testing.T) {
	for _, sample := range []struct {
		err  error
		code string
	}{{model.ErrGoogleAccountExists, "google_account_exists"}, {model.ErrGoogleAccountConflict, "google_account_conflict"}, {model.ErrGoogleEmailMismatch, "google_email_mismatch"}} {
		t.Run(sample.code, func(t *testing.T) {
			f := newControllerGoogleFixture(t)
			cookie := f.start(t, false)
			f.auth.fail = sample.err
			assertGoogleRedirect(t, f.request("GET", f.callbackPath(), cookie), "/?auth_error="+sample.code)
			if f.auth.sessions != 0 {
				t.Fatal("identity conflicts must never create login sessions")
			}
		})
	}
}

func TestGoogleControllerCanonicalizesBeforeCreatingChallenge(t *testing.T) {
	f := newControllerGoogleFixture(t)
	req := httptest.NewRequest("POST", "https://old-alias.example/auth/google/start", nil)
	rec := httptest.NewRecorder()
	f.router.ServeHTTP(rec, req)
	assertGoogleRedirect(t, rec, "https://finance.example/")
	if f.store.creates != 0 || len(rec.Result().Cookies()) != 0 {
		t.Fatal("canonical redirect must happen before creating host-bound challenge")
	}
}

func TestGoogleControllerDisabledProviderKeepsPasswordFlowAvailable(t *testing.T) {
	gin.SetMode(gin.TestMode)
	c := NewGoogleAuthController(usecase.NewAuthUsecase(nil), nil)
	router := gin.New()
	router.GET("/auth/providers", c.Providers)
	router.POST("/auth/google/start", c.Start)
	router.GET("/auth/google/callback", c.Callback)
	for _, sample := range []struct {
		method, path string
		status       int
	}{{"GET", "/auth/providers", 200}, {"POST", "/auth/google/start", 303}, {"GET", "/auth/google/callback", 303}} {
		rec := httptest.NewRecorder()
		router.ServeHTTP(rec, httptest.NewRequest(sample.method, sample.path, nil))
		if rec.Code != sample.status {
			t.Fatal("missing Google configuration must not panic")
		}
		if sample.path == "/auth/providers" && strings.TrimSpace(rec.Body.String()) != "{\"google\":false}" {
			t.Fatal("unconfigured Google button must remain hidden")
		}
	}
}
