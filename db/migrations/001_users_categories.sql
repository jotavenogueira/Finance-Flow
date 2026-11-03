CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE sessions (
    token_hash CHAR(64) PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_expires_at_idx ON sessions(expires_at);

CREATE TABLE categories (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id),
    name VARCHAR(100) NOT NULL CHECK (length(btrim(name)) > 0),
    UNIQUE (user_id, id)
);
CREATE UNIQUE INDEX categories_user_name_idx ON categories(user_id, lower(name));

-- Também permite iniciar um banco novo, sem apagar a tabela existente.
CREATE TABLE IF NOT EXISTS transaction (
    id SERIAL PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    amount NUMERIC(10, 2) NOT NULL,
    type VARCHAR(15) NOT NULL,
    category VARCHAR(100) NOT NULL,
    date DATE NOT NULL
);

-- Os registros antigos continuam intactos e sem proprietário. Não são
-- atribuídos automaticamente ao primeiro usuário que se cadastrar.
ALTER TABLE transaction ALTER COLUMN category TYPE VARCHAR(100);
ALTER TABLE transaction ADD COLUMN user_id INTEGER REFERENCES users(id);
ALTER TABLE transaction ADD COLUMN category_id INTEGER;
ALTER TABLE transaction ADD CONSTRAINT transaction_category_owner_fk
    FOREIGN KEY (user_id, category_id)
    REFERENCES categories(user_id, id) MATCH FULL;
ALTER TABLE transaction ADD CONSTRAINT transaction_owner_category_pair
    CHECK ((user_id IS NULL AND category_id IS NULL)
        OR (user_id IS NOT NULL AND category_id IS NOT NULL));
CREATE INDEX transaction_user_date_idx ON transaction(user_id, date DESC, id DESC);
