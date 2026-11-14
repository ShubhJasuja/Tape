
-- Apply as the schema owner after V6, only when provider watchlist storage is authorized.
GRANT SELECT, INSERT, DELETE ON market_watchlist TO tape_runtime;
-- Grant the metadata table only when the agreement authorizes durable catalog storage.
-- GRANT SELECT, INSERT, UPDATE (instrument, synchronized_at) ON market_instrument_cache TO tape_runtime;
