ALTER TABLE instruments
    ALTER COLUMN tick_size_paise DROP NOT NULL,
    ADD COLUMN source text NOT NULL DEFAULT 'TAPE_LEGACY',
    ADD COLUMN source_key text,
    ADD COLUMN isin text CHECK (isin ~ '^[A-Z]{2}[A-Z0-9]{9}[0-9]$'),
    ADD COLUMN instrument_type text NOT NULL DEFAULT 'EQUITY' CHECK (instrument_type IN ('EQUITY','SME_EQUITY')),
    ADD COLUMN listing_status text NOT NULL DEFAULT 'ACTIVE' CHECK (listing_status IN ('ACTIVE','INACTIVE','DELISTED')),
    ADD COLUMN synchronized_at timestamptz,
    ADD COLUMN synthetic_key text UNIQUE,
    ADD COLUMN fictional boolean NOT NULL DEFAULT false;

UPDATE instruments SET source='TAPE_BUILTIN', source_key=exchange || ':' || symbol,
    synthetic_key=exchange || ':' || symbol, tick_size_paise=NULL,
    synchronized_at=clock_timestamp()
WHERE id IN (
    '43446c65-7245-4400-a100-000000000001','43446c65-7245-4400-a100-000000000002',
    '43446c65-7245-4400-a100-000000000003','43446c65-7245-4400-a100-000000000004',
    '43446c65-7245-4400-a100-000000000005','43446c65-7245-4400-a100-000000000006',
    '43446c65-7245-4400-a100-000000000007','43446c65-7245-4400-a100-000000000008',
    '43446c65-7245-4400-a100-000000000009'
) AND exchange || ':' || symbol IN
    ('NSE:RELIANCE','NSE:TCS','NSE:INFY','NSE:HDFCBANK','NSE:SBIN','NSE:ITC','NSE:BHARTIARTL','BSE:RELIANCE','BSE:TCS');

UPDATE instruments SET source_key=id::text WHERE source_key IS NULL;
ALTER TABLE instruments
    ALTER COLUMN source_key SET NOT NULL;
-- Administrative inserts without a provider key get a unique local identity.
ALTER TABLE instruments ALTER COLUMN source_key SET DEFAULT gen_random_uuid()::text;
ALTER TABLE instruments ADD UNIQUE (source, source_key);
ALTER TABLE instruments ADD CHECK (synthetic_key IS NULL OR (source='TAPE_BUILTIN' AND NOT fictional));
CREATE INDEX instruments_isin_idx ON instruments (isin);
CREATE INDEX instruments_discovery_idx ON instruments (exchange, instrument_type, symbol, id);

ALTER TABLE orders ADD COLUMN requested_symbol text;
UPDATE orders o SET requested_symbol=i.exchange || ':' || i.symbol FROM instruments i WHERE o.instrument_id=i.id;

-- Fictional discovery data. These identifiers and companies are invented, including the ZZ codes.
INSERT INTO instruments (id,exchange,symbol,name,source,source_key,isin,instrument_type,synchronized_at,fictional)
SELECT gen_random_uuid(), CASE WHEN n%2=0 THEN 'NSE' ELSE 'BSE' END,
    'FICT' || lpad(n::text,3,'0'),
    (ARRAY['Monsoon','Riverstone','Saffron','Blue Banyan','Copperleaf','Lotus Ridge','Silver Kite','Amber Coast'])[1+(n-1)%8]
    || ' ' ||
    (ARRAY['Systems','Textiles','Engineering','Logistics','Materials','Foods','Energy','Packaging','Cables','Ceramics','Components','Services'])[1+(n-1)/8]
    || ' (fictional)',
    'TAPE_FIXTURE','fixture-' || n,'ZZ' || lpad(n::text,10,'0'),
    CASE WHEN n%5=0 THEN 'SME_EQUITY' ELSE 'EQUITY' END,clock_timestamp(),true
FROM generate_series(1,96) n
ON CONFLICT (exchange,symbol) DO NOTHING;

CREATE TABLE catalog_sync_state (
    source text PRIMARY KEY CHECK (source = 'TAPE_FIXTURE'),
    last_snapshot_at timestamptz
);
