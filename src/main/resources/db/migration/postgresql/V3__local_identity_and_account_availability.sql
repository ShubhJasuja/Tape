CREATE TABLE local_credentials (
    user_id uuid PRIMARY KEY REFERENCES users (id),
    email text NOT NULL UNIQUE,
    password_hash text NOT NULL CHECK (length(password_hash) BETWEEN 60 AND 255),
    CHECK (length(email) BETWEEN 3 AND 254 AND email = lower(btrim(email)))
);

ALTER TABLE trading_accounts
    ADD COLUMN trading_enabled boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT permitted_market_data_disabled
        CHECK (mode <> 'PERMITTED_MARKET_DATA' OR NOT trading_enabled);
