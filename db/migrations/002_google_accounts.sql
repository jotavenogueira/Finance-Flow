-- Google-only accounts have no local password. Existing password hashes and
-- all categories, transactions, and sessions remain unchanged.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE google_accounts (
    subject VARCHAR(255) PRIMARY KEY CHECK (length(btrim(subject)) > 0),
    user_id INTEGER NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);