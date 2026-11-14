ALTER TABLE derivative_events ADD COLUMN fee_paise bigint NOT NULL DEFAULT 0
    CHECK (fee_paise BETWEEN 0 AND 9007199254740991 AND (kind = 'FILL' OR fee_paise = 0));
ALTER TABLE derivative_events ADD UNIQUE (account_id, request_id, contract_id, spec_version);

CREATE TABLE practice_option_orders (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES derivative_books(account_id),
    request_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    spec_version integer NOT NULL,
    side text NOT NULL CHECK (side IN ('BUY','SELL')),
    lots integer NOT NULL CHECK (lots BETWEEN 1 AND 100000),
    limit_paise bigint CHECK (limit_paise BETWEEN 1 AND 9007199254740991),
    fee_paise bigint NOT NULL CHECK (fee_paise BETWEEN 0 AND 9007199254740991),
    reservation_paise bigint NOT NULL CHECK (reservation_paise BETWEEN 0 AND 9007199254740991),
    status text NOT NULL CHECK (status IN ('OPEN','FILLED','CANCELLED','EXPIRED','REJECTED')),
    reason text,
    fill_request_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (account_id, request_id),
    FOREIGN KEY (contract_id, spec_version) REFERENCES derivative_specs(contract_id, version),
    FOREIGN KEY (account_id, fill_request_id, contract_id, spec_version)
        REFERENCES derivative_events(account_id, request_id, contract_id, spec_version),
    CHECK ((status = 'FILLED') = (fill_request_id IS NOT NULL)),
    CHECK (fill_request_id IS NULL OR fill_request_id = id),
    CHECK ((status = 'REJECTED') = (reason IS NOT NULL)),
    CHECK (status <> 'OPEN' OR limit_paise IS NOT NULL),
    CHECK (updated_at >= created_at)
);

CREATE INDEX practice_option_orders_work ON practice_option_orders(id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX practice_option_orders_one_open ON practice_option_orders(account_id, contract_id) WHERE status = 'OPEN';
CREATE INDEX practice_option_orders_history ON practice_option_orders(account_id, created_at DESC, id DESC);
CREATE INDEX derivative_positions_by_contract ON derivative_positions(contract_id, account_id);

-- Small deterministic practice chain. Its namespace is independent of exchange and provider catalogs.
INSERT INTO derivative_contracts(id,source,source_key,exchange,underlying_key,underlying_kind,kind,expiry_at,strike_paise,settlement)
SELECT md5('tape-practice-v1:' || expiry || ':' || strike || ':' || kind)::uuid,
       'TAPE_OPTIONS_PRACTICE_V1', expiry || ':' || strike || ':' || kind,
       'NSE','PRACTICE_INDEX','INDEX',kind,(expiry || 'T15:30:00+05:30')::timestamptz,strike,'CASH'
FROM unnest(ARRAY['2027-06-24','2027-12-30','2028-12-28','2029-12-27','2030-12-26']) AS dates(expiry)
CROSS JOIN unnest(ARRAY[95000,100000,105000]) AS strikes(strike)
CROSS JOIN unnest(ARRAY['CALL','PUT']) AS kinds(kind);

INSERT INTO derivative_specs(contract_id,version,effective_at,lot_size,multiplier,tick_paise,
    initial_margin_paise,maintenance_margin_paise,max_lots,max_order_notional_paise)
SELECT id,1,'2026-01-01T00:00:00Z',10,1,5,200000,200000,50,10000000
FROM derivative_contracts WHERE source = 'TAPE_OPTIONS_PRACTICE_V1';
