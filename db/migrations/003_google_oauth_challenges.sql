CREATE TABLE google_oauth_challenges (
    state_hash CHAR(64) PRIMARY KEY,
    browser_hash CHAR(64) NOT NULL,
    nonce VARCHAR(128) NOT NULL,
    pkce_verifier VARCHAR(128) NOT NULL,
    link_user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    link_session_hash CHAR(64) REFERENCES sessions(token_hash) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK ((link_user_id IS NULL) = (link_session_hash IS NULL))
);
CREATE INDEX google_oauth_challenges_expiry_idx ON google_oauth_challenges(expires_at);
