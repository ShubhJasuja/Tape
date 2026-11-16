ALTER TABLE practice_option_orders RENAME TO practice_derivative_orders;
ALTER TABLE practice_derivative_orders ADD COLUMN reduce_only boolean NOT NULL DEFAULT false;

CREATE TABLE practice_future_sessions (
    contract_id uuid NOT NULL REFERENCES derivative_contracts(id),
    session_day date NOT NULL,
    mark_at timestamptz NOT NULL,
    PRIMARY KEY (contract_id,session_day),
    UNIQUE (contract_id,mark_at),
    CHECK (mark_at = ((session_day + time '15:30') AT TIME ZONE 'Asia/Kolkata'))
);

CREATE TRIGGER practice_future_sessions_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON practice_future_sessions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_immutable_record_change();

INSERT INTO derivative_contracts(id,source,source_key,exchange,underlying_key,underlying_kind,kind,expiry_at,strike_paise,settlement)
SELECT md5('tape-futures-v1:' || expiry)::uuid,'TAPE_FUTURES_PRACTICE_V1',expiry,'NSE','PRACTICE_INDEX','INDEX','FUTURE',
       (expiry || 'T15:30:00+05:30')::timestamptz,0,'CASH'
FROM unnest(ARRAY['2027-06-24','2027-12-30','2028-12-28']) AS dates(expiry);

INSERT INTO derivative_specs(contract_id,version,effective_at,lot_size,multiplier,tick_paise,
    initial_margin_paise,maintenance_margin_paise,max_lots,max_order_notional_paise)
SELECT id,1,'2026-01-01T00:00:00Z',10,2,5,200000,150000,10,25000000
FROM derivative_contracts WHERE source='TAPE_FUTURES_PRACTICE_V1';

INSERT INTO practice_future_sessions(contract_id,session_day,mark_at)
SELECT c.id, day, (day + time '15:30') AT TIME ZONE 'Asia/Kolkata'
FROM derivative_contracts c
CROSS JOIN LATERAL (
    SELECT date '2026-01-01' + n AS day FROM generate_series(0,
        (c.expiry_at AT TIME ZONE 'Asia/Kolkata')::date - date '2026-01-01' - 1) n
) sessions
WHERE c.source='TAPE_FUTURES_PRACTICE_V1';
