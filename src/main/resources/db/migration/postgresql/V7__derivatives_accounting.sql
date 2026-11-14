
CREATE TABLE derivative_contracts (
    id uuid PRIMARY KEY,
    source text NOT NULL CHECK (source <> ''),
    source_key text NOT NULL CHECK (source_key <> ''),
    exchange text NOT NULL CHECK (exchange IN ('NSE','BSE')),
    underlying_key text NOT NULL CHECK (underlying_key <> ''),
    underlying_kind text NOT NULL CHECK (underlying_kind IN ('INDEX','STOCK')),
    kind text NOT NULL CHECK (kind IN ('FUTURE','CALL','PUT')),
    expiry_at timestamptz NOT NULL,
    strike_paise bigint NOT NULL CHECK (strike_paise BETWEEN 0 AND 9007199254740991),
    settlement text NOT NULL CHECK (settlement IN ('CASH','PHYSICAL')),
    CHECK ((kind = 'FUTURE') = (strike_paise = 0)),
    CHECK (underlying_kind <> 'INDEX' OR settlement = 'CASH'),
    UNIQUE (source, source_key),
    UNIQUE (exchange, underlying_key, kind, expiry_at, strike_paise)
);

CREATE TABLE derivative_specs (
    contract_id uuid NOT NULL REFERENCES derivative_contracts(id),
    version integer NOT NULL CHECK (version > 0),
    effective_at timestamptz NOT NULL,
    lot_size integer NOT NULL CHECK (lot_size > 0),
    multiplier integer NOT NULL CHECK (multiplier > 0),
    tick_paise bigint NOT NULL CHECK (tick_paise BETWEEN 1 AND 9007199254740991),
    initial_margin_paise bigint NOT NULL CHECK (initial_margin_paise BETWEEN 1 AND 9007199254740991),
    maintenance_margin_paise bigint NOT NULL CHECK (maintenance_margin_paise BETWEEN 1 AND initial_margin_paise),
    max_lots integer NOT NULL CHECK (max_lots BETWEEN 1 AND 100000),
    max_order_notional_paise bigint NOT NULL CHECK (max_order_notional_paise BETWEEN 1 AND 9007199254740991),
    PRIMARY KEY (contract_id, version),
    UNIQUE (contract_id, effective_at)
);

CREATE TABLE derivative_books (
    account_id uuid PRIMARY KEY REFERENCES trading_accounts(id),
    cash_paise bigint NOT NULL CHECK (cash_paise BETWEEN -9007199254740991 AND 9007199254740991),
    reserved_paise bigint NOT NULL CHECK (reserved_paise BETWEEN 0 AND 9007199254740991),
    collateral_paise bigint NOT NULL DEFAULT 0 CHECK (collateral_paise BETWEEN 0 AND 9007199254740991),
    CHECK (reserved_paise + collateral_paise <= 9007199254740991)
);

CREATE TABLE derivative_positions (
    account_id uuid NOT NULL REFERENCES derivative_books(account_id),
    contract_id uuid NOT NULL,
    spec_version integer NOT NULL,
    lots bigint NOT NULL CHECK (lots BETWEEN -100000 AND 100000 AND lots <> 0),
    basis_paise bigint NOT NULL CHECK (basis_paise BETWEEN 0 AND 9007199254740991),
    collateral_paise bigint NOT NULL CHECK (collateral_paise BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY (account_id, contract_id),
    FOREIGN KEY (contract_id, spec_version) REFERENCES derivative_specs(contract_id, version)
);

CREATE TABLE derivative_events (
    seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES derivative_books(account_id),
    request_id uuid NOT NULL,
    contract_id uuid,
    spec_version integer,
    kind text NOT NULL CHECK (kind IN ('OPEN','RESERVE','FILL','MARK','SETTLE')),
    price_paise bigint CHECK (price_paise BETWEEN 0 AND 9007199254740991),
    session_day date,
    signed_lots bigint NOT NULL CHECK (signed_lots BETWEEN -100000 AND 100000),
    basis_delta_paise bigint NOT NULL CHECK (basis_delta_paise BETWEEN -9007199254740991 AND 9007199254740991),
    premium_paise bigint NOT NULL CHECK (premium_paise BETWEEN -9007199254740991 AND 9007199254740991),
    cash_delta_paise bigint NOT NULL CHECK (cash_delta_paise BETWEEN -9007199254740991 AND 9007199254740991),
    reserve_delta_paise bigint NOT NULL CHECK (reserve_delta_paise BETWEEN -9007199254740991 AND 9007199254740991),
    collateral_delta_paise bigint NOT NULL CHECK (collateral_delta_paise BETWEEN -9007199254740991 AND 9007199254740991),
    realized_paise bigint NOT NULL CHECK (realized_paise BETWEEN -9007199254740991 AND 9007199254740991),
    delivery_units bigint NOT NULL DEFAULT 0,
    delivery_cash_paise bigint NOT NULL DEFAULT 0 CHECK (delivery_cash_paise BETWEEN -9007199254740991 AND 9007199254740991),
    delivery_collateral_paise bigint NOT NULL DEFAULT 0 CHECK (delivery_collateral_paise BETWEEN 0 AND 9007199254740991),
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    UNIQUE (account_id, request_id),
    FOREIGN KEY (contract_id, spec_version) REFERENCES derivative_specs(contract_id, version) MATCH FULL,
    CHECK ((kind IN ('OPEN','RESERVE')) = (contract_id IS NULL)),
    CHECK ((kind IN ('OPEN','RESERVE')) = (price_paise IS NULL)),
    CHECK ((kind IN ('MARK','SETTLE')) = (session_day IS NOT NULL)),
    CHECK (kind NOT IN ('MARK','RESERVE') OR signed_lots = 0),
    CHECK (kind <> 'FILL' OR signed_lots <> 0),
    CHECK (kind <> 'OPEN' OR (cash_delta_paise > 0 AND reserve_delta_paise BETWEEN 0 AND cash_delta_paise
        AND signed_lots = 0 AND basis_delta_paise = 0 AND premium_paise = 0 AND collateral_delta_paise = 0 AND realized_paise = 0)),
    CHECK (kind <> 'RESERVE' OR (cash_delta_paise = 0 AND basis_delta_paise = 0 AND premium_paise = 0
        AND collateral_delta_paise = 0 AND realized_paise = 0 AND reserve_delta_paise <> 0)),
    CHECK (kind = 'SETTLE' OR (delivery_units = 0 AND delivery_cash_paise = 0 AND delivery_collateral_paise = 0)),
    CHECK (delivery_units <> 0 OR (delivery_cash_paise = 0 AND delivery_collateral_paise = 0)),
    CHECK (occurred_at <= recorded_at)
);

CREATE UNIQUE INDEX derivative_one_open ON derivative_events(account_id) WHERE kind = 'OPEN';
CREATE UNIQUE INDEX derivative_one_mark ON derivative_events(account_id, contract_id, session_day) WHERE kind = 'MARK';
CREATE UNIQUE INDEX derivative_one_settlement ON derivative_events(account_id, contract_id) WHERE kind = 'SETTLE';
CREATE INDEX derivative_events_book ON derivative_events(account_id, seq);
CREATE INDEX derivative_events_contract ON derivative_events(account_id, contract_id, seq DESC);

CREATE TABLE derivative_lifecycle_retries (
    account_id uuid NOT NULL,
    request_id uuid NOT NULL,
    original_request_id uuid NOT NULL,
    PRIMARY KEY (account_id, request_id),
    FOREIGN KEY (account_id, original_request_id) REFERENCES derivative_events(account_id, request_id),
    CHECK (request_id <> original_request_id)
);

-- Contract replacements are recorded under a new identity and economic changes under a new version.
CREATE TRIGGER derivative_contracts_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON derivative_contracts
    FOR EACH STATEMENT EXECUTE FUNCTION reject_immutable_record_change();
CREATE TRIGGER derivative_specs_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON derivative_specs
    FOR EACH STATEMENT EXECUTE FUNCTION reject_immutable_record_change();
CREATE TRIGGER derivative_events_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON derivative_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_immutable_record_change();
CREATE TRIGGER derivative_lifecycle_retries_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON derivative_lifecycle_retries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_immutable_record_change();
