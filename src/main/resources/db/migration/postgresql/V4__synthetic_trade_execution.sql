UPDATE trading_accounts SET trading_enabled=true WHERE mode='DEMO';

ALTER TABLE orders ADD COLUMN rejection_code text;
ALTER TABLE orders ADD CONSTRAINT order_rejection_reason
    CHECK (rejection_code IS NULL OR status = 'REJECTED');
CREATE INDEX orders_open_account_idx ON orders (account_id, created_at, id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX executions_one_synthetic_fill_per_order
    ON executions (order_id) WHERE quote_source = 'TAPE_SYNTHETIC_V1';

-- Row locks require UPDATE privilege. Keep account metadata immutable to the runtime role.
CREATE FUNCTION lock_tape_trading_account(owner_id uuid, account_uuid uuid)
RETURNS text LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog AS $$
    SELECT a.mode FROM public.trading_accounts a
    WHERE a.user_id = owner_id AND a.id = account_uuid FOR NO KEY UPDATE
$$;
REVOKE ALL ON FUNCTION lock_tape_trading_account(uuid, uuid) FROM PUBLIC;

INSERT INTO instruments (id, exchange, symbol, name, tick_size_paise) VALUES
    ('43446c65-7245-4400-a100-000000000001','NSE','RELIANCE','Reliance Industries',1),
    ('43446c65-7245-4400-a100-000000000002','NSE','TCS','Tata Consultancy Services',1),
    ('43446c65-7245-4400-a100-000000000003','NSE','INFY','Infosys',1),
    ('43446c65-7245-4400-a100-000000000004','NSE','HDFCBANK','HDFC Bank',1),
    ('43446c65-7245-4400-a100-000000000005','NSE','SBIN','State Bank of India',1),
    ('43446c65-7245-4400-a100-000000000006','NSE','ITC','ITC',1),
    ('43446c65-7245-4400-a100-000000000007','NSE','BHARTIARTL','Bharti Airtel',1),
    ('43446c65-7245-4400-a100-000000000008','BSE','RELIANCE','Reliance Industries',1),
    ('43446c65-7245-4400-a100-000000000009','BSE','TCS','Tata Consultancy Services',1)
ON CONFLICT (exchange, symbol) DO NOTHING;
