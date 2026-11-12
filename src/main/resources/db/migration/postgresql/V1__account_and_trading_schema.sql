CREATE TABLE users (
    id uuid PRIMARY KEY,
    identity_issuer text NOT NULL CHECK (btrim(identity_issuer) <> ''),
    identity_subject text NOT NULL CHECK (btrim(identity_subject) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (identity_issuer, identity_subject)
);

CREATE TABLE trading_accounts (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    mode text NOT NULL CHECK (mode IN ('DEMO', 'PERMITTED_MARKET_DATA')),
    currency text NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, mode)
);

-- The paise ceiling is the largest integer that a JavaScript number represents exactly.
CREATE TABLE wallets (
    account_id uuid PRIMARY KEY REFERENCES trading_accounts (id),
    balance_paise bigint NOT NULL CHECK (balance_paise BETWEEN 0 AND 9007199254740991),
    reserved_paise bigint NOT NULL DEFAULT 0,
    CHECK (reserved_paise BETWEEN 0 AND balance_paise)
);

CREATE TABLE instruments (
    id uuid PRIMARY KEY,
    exchange text NOT NULL CHECK (exchange IN ('NSE', 'BSE')),
    symbol text NOT NULL CHECK (btrim(symbol) <> ''),
    name text NOT NULL CHECK (btrim(name) <> ''),
    tick_size_paise bigint NOT NULL CHECK (tick_size_paise BETWEEN 1 AND 100000000),
    UNIQUE (exchange, symbol)
);

CREATE TABLE orders (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES trading_accounts (id),
    instrument_id uuid NOT NULL REFERENCES instruments (id),
    idempotency_key uuid NOT NULL,
    side text NOT NULL CHECK (side IN ('BUY', 'SELL')),
    kind text NOT NULL CHECK (kind IN ('MARKET', 'LIMIT', 'STOP')),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 100000),
    trigger_price_paise bigint CHECK (trigger_price_paise BETWEEN 1 AND 100000000),
    status text NOT NULL CHECK (status IN ('OPEN', 'FILLED', 'CANCELLED', 'REJECTED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((kind = 'MARKET') = (trigger_price_paise IS NULL)),
    CHECK (updated_at >= created_at),
    UNIQUE (account_id, idempotency_key),
    UNIQUE (id, account_id, instrument_id)
);
CREATE INDEX orders_account_history_idx ON orders (account_id, created_at DESC, id);
CREATE INDEX orders_open_by_instrument_idx ON orders (instrument_id, created_at, id) WHERE status = 'OPEN';

CREATE TABLE executions (
    id uuid PRIMARY KEY,
    execution_sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    order_id uuid NOT NULL,
    account_id uuid NOT NULL,
    instrument_id uuid NOT NULL,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 100000),
    price_paise bigint NOT NULL CHECK (price_paise BETWEEN 1 AND 100000000),
    gross_paise bigint NOT NULL CHECK (gross_paise = price_paise::numeric * quantity),
    fee_paise bigint NOT NULL CHECK (fee_paise BETWEEN 0 AND gross_paise),
    executed_at timestamptz NOT NULL,
    quote_source text,
    quote_at timestamptz,
    CHECK ((quote_source IS NULL) = (quote_at IS NULL)),
    FOREIGN KEY (order_id, account_id, instrument_id) REFERENCES orders (id, account_id, instrument_id),
    UNIQUE (id, account_id)
);
CREATE INDEX executions_account_sequence_idx ON executions (account_id, execution_sequence);
CREATE INDEX executions_order_idx ON executions (order_id);

CREATE TABLE cash_ledger_entries (
    id uuid PRIMARY KEY,
    entry_sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    account_id uuid NOT NULL REFERENCES wallets (account_id),
    execution_id uuid,
    kind text NOT NULL CHECK (kind IN ('OPENING_BALANCE', 'TRADE_DEBIT', 'TRADE_CREDIT', 'FEE')),
    amount_paise bigint NOT NULL CHECK (amount_paise BETWEEN -9007199254740991 AND 9007199254740991),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((kind = 'OPENING_BALANCE') = (execution_id IS NULL)),
    CHECK (
        (kind IN ('OPENING_BALANCE', 'TRADE_CREDIT') AND amount_paise > 0)
        OR (kind IN ('TRADE_DEBIT', 'FEE') AND amount_paise < 0)
    ),
    FOREIGN KEY (execution_id, account_id) REFERENCES executions (id, account_id),
    UNIQUE (execution_id, kind)
);
CREATE UNIQUE INDEX cash_ledger_one_opening_idx ON cash_ledger_entries (account_id) WHERE kind = 'OPENING_BALANCE';
CREATE INDEX cash_ledger_account_sequence_idx ON cash_ledger_entries (account_id, entry_sequence);

CREATE TABLE positions (
    account_id uuid NOT NULL REFERENCES trading_accounts (id),
    instrument_id uuid NOT NULL REFERENCES instruments (id),
    quantity bigint NOT NULL CHECK (quantity BETWEEN 0 AND 9007199254740991),
    reserved_quantity bigint NOT NULL DEFAULT 0,
    cost_paise bigint NOT NULL CHECK (cost_paise BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY (account_id, instrument_id),
    CHECK (reserved_quantity BETWEEN 0 AND quantity),
    CHECK (quantity > 0 OR cost_paise = 0)
);
CREATE INDEX positions_instrument_idx ON positions (instrument_id);

CREATE TABLE watchlists (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    name text NOT NULL CHECK (btrim(name) <> ''),
    UNIQUE (user_id, name)
);

CREATE TABLE watchlist_items (
    watchlist_id uuid NOT NULL REFERENCES watchlists (id) ON DELETE CASCADE,
    instrument_id uuid NOT NULL REFERENCES instruments (id),
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (watchlist_id, instrument_id)
);
CREATE INDEX watchlist_items_instrument_idx ON watchlist_items (instrument_id);
