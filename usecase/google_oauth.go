package usecase

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
	"github.com/jotavenogueira/Finance-Flow/model"
	"golang.org/x/oauth2"
)

const GoogleChallengeDuration = 10 * time.Minute

type GoogleOAuthConfig struct {
	ClientID     string
	ClientSecret string
	RedirectURL  string
}

func GoogleOAuthConfigFromEnv() (GoogleOAuthConfig, error) {
	config := GoogleOAuthConfig{
		ClientID:     strings.TrimSpace(os.Getenv("GOOGLE_CLIENT_ID")),
		ClientSecret: strings.TrimSpace(os.Getenv("GOOGLE_CLIENT_SECRET")),
		RedirectURL:  strings.TrimSpace(os.Getenv("GOOGLE_REDIRECT_URL")),
	}
	if config.ClientID == "" && config.ClientSecret == "" && config.RedirectURL == "" {
		return config, nil
	}
	if err := config.Validate(); err != nil {
		return GoogleOAuthConfig{}, err
	}
	return config, nil
}

func (c GoogleOAuthConfig) Enabled() bool {
	return c.ClientID != "" && c.ClientSecret != "" && c.RedirectURL != ""
}

func (c GoogleOAuthConfig) Validate() error {
	redirect, err := url.Parse(c.RedirectURL)
	if !c.Enabled() || err != nil || redirect.Host == "" || redirect.User != nil ||
		redirect.RawQuery != "" || redirect.Fragment != "" || redirect.Path != "/auth/google/callback" ||
		(redirect.Scheme != "https" && !(redirect.Scheme == "http" &&
			(redirect.Hostname() == "localhost" || redirect.Hostname() == "127.0.0.1"))) {
		return errors.New("configure GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET e GOOGLE_REDIRECT_URL com callback HTTPS (HTTP somente em localhost)")
	}
	return nil
}

type GoogleOAuthStore interface {
	CreateGoogleChallenge(context.Context, model.GoogleOAuthChallenge) error
	ConsumeGoogleChallenge(context.Context, string, string) (model.GoogleOAuthChallenge, error)
	GoogleLinkSessionActive(context.Context, model.GoogleOAuthChallenge) (bool, error)
}

type GoogleIdentityProvider interface {
	AuthorizationURL(state, verifier, nonce string) string
	Identity(context.Context, string, string, string) (model.GoogleIdentity, error)
}

type GoogleOAuth struct {
	config   GoogleOAuthConfig
	store    GoogleOAuthStore
	provider GoogleIdentityProvider
}

func NewGoogleOAuth(config GoogleOAuthConfig, store GoogleOAuthStore, provider GoogleIdentityProvider) *GoogleOAuth {
	if !config.Enabled() || config.Validate() != nil {
		return nil
	}
	return &GoogleOAuth{config: config, store: store, provider: provider}
}

func (g *GoogleOAuth) Enabled() bool { return g != nil }
func (g *GoogleOAuth) CanonicalOrigin() string {
	if g == nil {
		return ""
	}
	redirect, _ := url.Parse(g.config.RedirectURL)
	return redirect.Scheme + "://" + redirect.Host
}
func (g *GoogleOAuth) UsesHTTPS() bool {
	return g != nil && strings.HasPrefix(g.config.RedirectURL, "https://")
}

func (g *GoogleOAuth) Begin(ctx context.Context, browserToken string, userID int, sessionToken string) (string, error) {
	if g == nil || !validSessionToken(browserToken) || (userID > 0 && !validSessionToken(sessionToken)) {
		return "", model.ErrInvalidCredentials
	}
	state, err := NewGoogleBrowserToken()
	if err != nil {
		return "", err
	}
	nonce, err := NewGoogleBrowserToken()
	if err != nil {
		return "", err
	}
	challenge := model.GoogleOAuthChallenge{
		StateHash: hashSessionToken(state), BrowserHash: hashSessionToken(browserToken),
		Nonce: nonce, Verifier: oauth2.GenerateVerifier(), LinkUserID: userID,
		ExpiresAt: time.Now().UTC().Add(GoogleChallengeDuration),
	}
	if userID > 0 {
		challenge.LinkSessionHash = hashSessionToken(sessionToken)
	}
	if err := g.store.CreateGoogleChallenge(ctx, challenge); err != nil {
		return "", err
	}
	return g.provider.AuthorizationURL(state, challenge.Verifier, nonce), nil
}

func (g *GoogleOAuth) Consume(ctx context.Context, state, browserToken string) (model.GoogleOAuthChallenge, error) {
	if g == nil || !validSessionToken(state) || !validSessionToken(browserToken) {
		return model.GoogleOAuthChallenge{}, model.ErrInvalidCredentials
	}
	return g.store.ConsumeGoogleChallenge(ctx, hashSessionToken(state), hashSessionToken(browserToken))
}

func (g *GoogleOAuth) Identity(ctx context.Context, challenge model.GoogleOAuthChallenge, code string) (model.GoogleIdentity, error) {
	if g == nil || code == "" || len(code) > 4096 {
		return model.GoogleIdentity{}, model.ErrInvalidCredentials
	}
	return g.provider.Identity(ctx, code, challenge.Verifier, challenge.Nonce)
}

func (g *GoogleOAuth) LinkSessionActive(ctx context.Context, challenge model.GoogleOAuthChallenge) (bool, error) {
	return g.store.GoogleLinkSessionActive(ctx, challenge)
}

func NewGoogleBrowserToken() (string, error) {
	token := make([]byte, 32)
	if _, err := rand.Read(token); err != nil {
		return "", errors.New("não foi possível iniciar autenticação Google")
	}
	return base64.RawURLEncoding.EncodeToString(token), nil
}

// Google valida o código e a assinatura do ID token. Tokens de acesso Google
// são usados somente nesta chamada; não são armazenados nem enviados à interface.
type googleIdentityProvider struct {
	config   oauth2.Config
	verifier *oidc.IDTokenVerifier
	client   *http.Client
}

func NewGoogleIdentityProvider(config GoogleOAuthConfig) GoogleIdentityProvider {
	client := &http.Client{Timeout: 10 * time.Second}
	background := oidc.ClientContext(context.Background(), client)
	keys := oidc.NewRemoteKeySet(background, "https://www.googleapis.com/oauth2/v3/certs")
	return &googleIdentityProvider{
		config: oauth2.Config{
			ClientID: config.ClientID, ClientSecret: config.ClientSecret, RedirectURL: config.RedirectURL,
			Scopes: []string{oidc.ScopeOpenID, "email", "profile"},
			Endpoint: oauth2.Endpoint{
				AuthURL:   "https://accounts.google.com/o/oauth2/v2/auth",
				TokenURL:  "https://oauth2.googleapis.com/token",
				AuthStyle: oauth2.AuthStyleInParams,
			},
		},
		verifier: oidc.NewVerifier("https://accounts.google.com", keys, &oidc.Config{
			ClientID: config.ClientID, SupportedSigningAlgs: []string{oidc.RS256},
		}),
		client: client,
	}
}

func (p *googleIdentityProvider) AuthorizationURL(state, verifier, nonce string) string {
	return p.config.AuthCodeURL(state, oauth2.AccessTypeOnline, oauth2.S256ChallengeOption(verifier),
		oidc.Nonce(nonce), oauth2.SetAuthURLParam("prompt", "select_account"))
}

func (p *googleIdentityProvider) Identity(ctx context.Context, code, verifier, nonce string) (model.GoogleIdentity, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	ctx = context.WithValue(ctx, oauth2.HTTPClient, p.client)
	token, err := p.config.Exchange(ctx, code, oauth2.VerifierOption(verifier))
	if err != nil {
		return model.GoogleIdentity{}, model.ErrInvalidCredentials
	}
	raw, ok := token.Extra("id_token").(string)
	if !ok || raw == "" {
		return model.GoogleIdentity{}, model.ErrInvalidCredentials
	}
	idToken, err := p.verifier.Verify(ctx, raw)
	if err != nil || subtle.ConstantTimeCompare([]byte(idToken.Nonce), []byte(nonce)) != 1 || nonce == "" {
		return model.GoogleIdentity{}, model.ErrInvalidCredentials
	}
	var claims struct {
		Email           string `json:"email"`
		EmailVerified   bool   `json:"email_verified"`
		Name            string `json:"name"`
		AuthorizedParty string `json:"azp"`
	}
	if err := idToken.Claims(&claims); err != nil || !claims.EmailVerified ||
		(claims.AuthorizedParty != "" && claims.AuthorizedParty != p.config.ClientID) ||
		(len(idToken.Audience) > 1 && claims.AuthorizedParty != p.config.ClientID) {
		return model.GoogleIdentity{}, model.ErrInvalidCredentials
	}
	return model.GoogleIdentity{Subject: idToken.Subject, Email: claims.Email, Name: claims.Name}, nil
}
