
CREATE TABLE market_watchlist (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source text NOT NULL CHECK (source = 'UPSTOX'),
    instrument_key text NOT NULL CHECK (
        instrument_key ~ '^(NSE_EQ|BSE_EQ|NSE_INDEX|NSE_FO)[|][A-Za-z0-9 ._&-]{1,65}$'
    ),
    PRIMARY KEY (user_id, source, instrument_key)
);

CREATE TABLE market_instrument_cache (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source text NOT NULL CHECK (source = 'UPSTOX'),
    instrument_key text NOT NULL CHECK (
        instrument_key ~ '^(NSE_EQ|BSE_EQ|NSE_INDEX|NSE_FO)[|][A-Za-z0-9 ._&-]{1,65}$'
    ),
    identity text NOT NULL,
    instrument jsonb NOT NULL CHECK (jsonb_typeof(instrument) = 'object'),
    synchronized_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, source, instrument_key)
);
