package usecase

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
	"github.com/jotavenogueira/Finance-Flow/model"
	"golang.org/x/oauth2"
)

type googleChallengeMemory struct {
	challenges map[string]model.GoogleOAuthChallenge
	active     bool
	creates    int
	consumes   int
}

func (s *googleChallengeMemory) CreateGoogleChallenge(_ context.Context, c model.GoogleOAuthChallenge) error {
	s.creates++
	s.challenges[c.StateHash] = c
	return nil
}
func (s *googleChallengeMemory) ConsumeGoogleChallenge(_ context.Context, state, browser string) (model.GoogleOAuthChallenge, error) {
	s.consumes++
	c, ok := s.challenges[state]
	if !ok || c.BrowserHash != browser || !c.ExpiresAt.After(time.Now()) || (c.LinkUserID > 0 && !s.active) {
		return model.GoogleOAuthChallenge{}, model.ErrInvalidCredentials
	}
	delete(s.challenges, state)
	return c, nil
}
func (s *googleChallengeMemory) GoogleLinkSessionActive(context.Context, model.GoogleOAuthChallenge) (bool, error) {
	return s.active, nil
}

type googleProviderMemory struct{ state, verifier, nonce string }

func (p *googleProviderMemory) AuthorizationURL(state, verifier, nonce string) string {
	p.state, p.verifier, p.nonce = state, verifier, nonce
	return "https://accounts.google.com/authorize?state=" + state
}
func (*googleProviderMemory) Identity(context.Context, string, string, string) (model.GoogleIdentity, error) {
	return model.GoogleIdentity{}, nil
}
func testGoogleConfig() GoogleOAuthConfig {
	return GoogleOAuthConfig{ClientID: "test-client", ClientSecret: "local-test-secret", RedirectURL: "https://finance.example/auth/google/callback"}
}

func TestGoogleChallengeBindsBrowserAndCanOnlyBeConsumedOnce(t *testing.T) {
	store := &googleChallengeMemory{challenges: map[string]model.GoogleOAuthChallenge{}, active: true}
	provider := &googleProviderMemory{}
	flow := NewGoogleOAuth(testGoogleConfig(), store, provider)
	browser, _ := NewGoogleBrowserToken()
	session, _ := NewGoogleBrowserToken()
	before := time.Now()
	if _, err := flow.Begin(context.Background(), browser, 7, session); err != nil {
		t.Fatal(err)
	}
	saved := store.challenges[hashSessionToken(provider.state)]
	if saved.BrowserHash != hashSessionToken(browser) || saved.LinkSessionHash != hashSessionToken(session) || saved.LinkUserID != 7 || saved.StateHash == provider.state || saved.BrowserHash == browser {
		t.Fatal("challenge must bind the browser and original authenticated session using hashes")
	}
	if saved.Nonce == "" || saved.Verifier == "" || saved.ExpiresAt.Before(before.Add(9*time.Minute)) || saved.ExpiresAt.After(time.Now().Add(11*time.Minute)) {
		t.Fatal("challenge needs unique nonce, verifier, and short expiry")
	}
	other, _ := NewGoogleBrowserToken()
	if _, err := flow.Consume(context.Background(), provider.state, other); !errors.Is(err, model.ErrInvalidCredentials) {
		t.Fatal("another browser must not claim this flow")
	}
	if len(store.challenges) != 1 {
		t.Fatal("mismatched browser must not consume the legitimate challenge")
	}
	if _, err := flow.Consume(context.Background(), provider.state, browser); err != nil {
		t.Fatal(err)
	}
	if _, err := flow.Consume(context.Background(), provider.state, browser); !errors.Is(err, model.ErrInvalidCredentials) {
		t.Fatal("callback replay must be rejected")
	}
}

func TestGoogleChallengeRejectsMalformedAndExpiredAttempts(t *testing.T) {
	store := &googleChallengeMemory{challenges: map[string]model.GoogleOAuthChallenge{}, active: true}
	provider := &googleProviderMemory{}
	flow := NewGoogleOAuth(testGoogleConfig(), store, provider)
	browser, _ := NewGoogleBrowserToken()
	if _, err := flow.Begin(context.Background(), "invalid", 0, ""); !errors.Is(err, model.ErrInvalidCredentials) || store.creates != 0 {
		t.Fatal("malformed browser tokens must not create challenges")
	}
	if _, err := flow.Begin(context.Background(), browser, 7, "invalid"); !errors.Is(err, model.ErrInvalidCredentials) || store.creates != 0 {
		t.Fatal("linking needs a valid original session token")
	}
	if _, err := flow.Consume(context.Background(), "invalid", browser); !errors.Is(err, model.ErrInvalidCredentials) || store.consumes != 0 {
		t.Fatal("malformed state must not reach storage")
	}
	if _, err := flow.Begin(context.Background(), browser, 0, ""); err != nil {
		t.Fatal(err)
	}
	c := store.challenges[hashSessionToken(provider.state)]
	c.ExpiresAt = time.Now().Add(-time.Second)
	store.challenges[c.StateHash] = c
	if _, err := flow.Consume(context.Background(), provider.state, browser); !errors.Is(err, model.ErrInvalidCredentials) {
		t.Fatal("expired attempts must fail")
	}
}

func TestGoogleConfigurationAllowsOnlyExplicitSecureCallback(t *testing.T) {
	good := testGoogleConfig()
	for _, redirect := range []string{"https://finance.example/auth/google/callback", "http://localhost:8081/auth/google/callback", "http://127.0.0.1:8081/auth/google/callback"} {
		good.RedirectURL = redirect
		if err := good.Validate(); err != nil {
			t.Fatalf("valid callback %q rejected", redirect)
		}
	}
	for _, redirect := range []string{"http://finance.example/auth/google/callback", "https://user:password@finance.example/auth/google/callback", "https://finance.example/other", "https://finance.example/auth/google/callback?next=evil", "https://finance.example/auth/google/callback#fragment", "//finance.example/auth/google/callback"} {
		good.RedirectURL = redirect
		if err := good.Validate(); err == nil {
			t.Fatalf("unsafe callback %q accepted", redirect)
		}
	}
	t.Setenv("GOOGLE_CLIENT_ID", "")
	t.Setenv("GOOGLE_CLIENT_SECRET", "")
	t.Setenv("GOOGLE_REDIRECT_URL", "")
	config, err := GoogleOAuthConfigFromEnv()
	if err != nil || config.Enabled() {
		t.Fatal("local password auth must work without Google configuration")
	}
	t.Setenv("GOOGLE_CLIENT_ID", "only-one-variable")
	if _, err := GoogleOAuthConfigFromEnv(); err == nil {
		t.Fatal("partial Google configuration must be rejected")
	}
}

func TestGoogleAuthorizationUsesCodePKCENonceAndIdentityOnlyScopes(t *testing.T) {
	provider := NewGoogleIdentityProvider(testGoogleConfig())
	verifier := oauth2.GenerateVerifier()
	location, err := url.Parse(provider.AuthorizationURL("test-state", verifier, "test-nonce"))
	if err != nil {
		t.Fatal(err)
	}
	q := location.Query()
	sum := sha256.Sum256([]byte(verifier))
	if location.Host != "accounts.google.com" || q.Get("response_type") != "code" || q.Get("state") != "test-state" || q.Get("nonce") != "test-nonce" || q.Get("code_challenge_method") != "S256" || q.Get("code_challenge") != base64.RawURLEncoding.EncodeToString(sum[:]) {
		t.Fatal("authorization must bind code, state, PKCE S256, and nonce")
	}
	if q.Get("access_type") != "online" || q.Get("scope") != "openid email profile" || strings.Contains(location.String(), "local-test-secret") {
		t.Fatal("authorization URL must request basic identity only and never expose client secret")
	}
}

func TestGoogleIdentityValidatesRealSignedTokens(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	wrongKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	var rawToken string
	exchanges := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/jwks" {
			_ = json.NewEncoder(w).Encode(map[string]any{"keys": []any{map[string]any{"kty": "RSA", "kid": "test-key", "alg": "RS256", "use": "sig", "n": base64.RawURLEncoding.EncodeToString(key.N.Bytes()), "e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.E)).Bytes())}}})
			return
		}
		if r.URL.Path != "/token" {
			http.NotFound(w, r)
			return
		}
		exchanges++
		if err := r.ParseForm(); err != nil {
			t.Error(err)
		}
		if r.Form.Get("code") != "test-code" || r.Form.Get("code_verifier") != "test-verifier" || r.Form.Get("client_id") != "test-client" || r.Form.Get("client_secret") != "local-test-secret" || r.Form.Get("redirect_uri") != "https://finance.example/auth/google/callback" {
			t.Error("token exchange must send PKCE verifier and confidential client credentials only to token endpoint")
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "test-access-token", "token_type": "Bearer", "id_token": rawToken})
	}))
	defer server.Close()
	p := NewGoogleIdentityProvider(testGoogleConfig()).(*googleIdentityProvider)
	p.config.Endpoint.TokenURL = server.URL + "/token"
	p.client = server.Client()
	ctx := oidc.ClientContext(context.Background(), server.Client())
	p.verifier = oidc.NewVerifier("https://accounts.google.com", oidc.NewRemoteKeySet(ctx, server.URL+"/jwks"), &oidc.Config{ClientID: "test-client", SupportedSigningAlgs: []string{oidc.RS256}})
	cases := []struct {
		name          string
		change        func(map[string]any)
		badSignature  bool
		expectedNonce string
		valid         bool
	}{
		{name: "valid", valid: true},
		{name: "Google legacy issuer", change: func(c map[string]any) { c["iss"] = "accounts.google.com" }, valid: true},
		{name: "wrong issuer", change: func(c map[string]any) { c["iss"] = "https://attacker.example" }},
		{name: "wrong audience", change: func(c map[string]any) { c["aud"] = "different-client" }},
		{name: "expired", change: func(c map[string]any) { c["exp"] = time.Now().Add(-time.Hour).Unix() }},
		{name: "wrong signature", badSignature: true},
		{name: "wrong nonce", change: func(c map[string]any) { c["nonce"] = "different-nonce" }},
		{name: "missing nonce", change: func(c map[string]any) { delete(c, "nonce") }},
		{name: "empty expected nonce", expectedNonce: "empty"},
		{name: "unverified email", change: func(c map[string]any) { c["email_verified"] = false }},
		{name: "missing email verification", change: func(c map[string]any) { delete(c, "email_verified") }},
		{name: "wrong authorized party", change: func(c map[string]any) { c["azp"] = "different-client" }},
		{name: "multiple audience missing party", change: func(c map[string]any) { c["aud"] = []string{"test-client", "another-client"} }},
		{name: "multiple audience correct party", change: func(c map[string]any) { c["aud"] = []string{"test-client", "another-client"}; c["azp"] = "test-client" }, valid: true},
	}
	for _, sample := range cases {
		t.Run(sample.name, func(t *testing.T) {
			claims := map[string]any{"iss": "https://accounts.google.com", "aud": "test-client", "sub": "stable-subject", "exp": time.Now().Add(time.Hour).Unix(), "iat": time.Now().Unix(), "nonce": "test-nonce", "email": "ana@example.com", "email_verified": true, "name": "Ana"}
			if sample.change != nil {
				sample.change(claims)
			}
			signingKey := key
			if sample.badSignature {
				signingKey = wrongKey
			}
			rawToken = signGoogleTestJWT(t, signingKey, claims)
			nonce := "test-nonce"
			if sample.expectedNonce == "empty" {
				nonce = ""
			}
			user, err := p.Identity(context.Background(), "test-code", "test-verifier", nonce)
			if sample.valid {
				if err != nil || user.Subject != "stable-subject" || user.Email != "ana@example.com" {
					t.Fatalf("valid signed identity rejected: %v", err)
				}
			} else if !errors.Is(err, model.ErrInvalidCredentials) || user.Subject != "" {
				t.Fatal("invalid signed identity must not be authenticated")
			}
		})
	}
	if exchanges != len(cases) {
		t.Fatal("all token validation cases should exercise real local code exchange")
	}
}

func signGoogleTestJWT(t *testing.T, key *rsa.PrivateKey, claims map[string]any) string {
	t.Helper()
	header, _ := json.Marshal(map[string]string{"alg": "RS256", "kid": "test-key", "typ": "JWT"})
	payload, err := json.Marshal(claims)
	if err != nil {
		t.Fatal(err)
	}
	unsigned := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(payload)
	digest := sha256.Sum256([]byte(unsigned))
	signature, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return unsigned + "." + base64.RawURLEncoding.EncodeToString(signature)
}
