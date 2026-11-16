CREATE TABLE market_watchlists (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source text NOT NULL CHECK (source = 'UPSTOX'),
    name text NOT NULL CHECK (name ~ '^[A-Za-z0-9][A-Za-z0-9 _-]{0,39}$' AND name = btrim(name)),
    PRIMARY KEY (user_id, source, name)
);

INSERT INTO market_watchlists(user_id, source, name)
SELECT DISTINCT user_id, source, 'Default' FROM market_watchlist;

ALTER TABLE market_watchlist
    ADD COLUMN watchlist_name text NOT NULL DEFAULT 'Default',
    DROP CONSTRAINT market_watchlist_pkey,
    ADD PRIMARY KEY (user_id, source, watchlist_name, instrument_key),
    ADD FOREIGN KEY (user_id, source, watchlist_name) REFERENCES market_watchlists(user_id, source, name) ON DELETE CASCADE,
    DROP CONSTRAINT market_watchlist_instrument_key_check,
    ADD CHECK (instrument_key ~ '^(NSE_EQ|BSE_EQ|NSE_INDEX|BSE_INDEX|NSE_FO)[|][A-Za-z0-9 ._&-]{1,65}$');

ALTER TABLE market_instrument_cache
    DROP CONSTRAINT market_instrument_cache_instrument_key_check,
    ADD CHECK (instrument_key ~ '^(NSE_EQ|BSE_EQ|NSE_INDEX|BSE_INDEX|NSE_FO)[|][A-Za-z0-9 ._&-]{1,65}$');
