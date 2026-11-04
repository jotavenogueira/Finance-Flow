package model

import "time"

// GoogleOAuthChallenge guarda a tentativa de login somente no servidor.
type GoogleOAuthChallenge struct {
	StateHash       string
	BrowserHash     string
	Nonce           string
	Verifier        string
	LinkUserID      int
	LinkSessionHash string
	ExpiresAt       time.Time
}
