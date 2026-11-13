-- Run as the schema owner after V5. tape_catalog_writer must already be a dedicated LOGIN role.
GRANT USAGE ON SCHEMA public TO tape_catalog_writer;
GRANT SELECT, INSERT, UPDATE (last_snapshot_at) ON catalog_sync_state TO tape_catalog_writer;
-- Restrict writes to fictional rows via the source-scoped policies below.
ALTER TABLE instruments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tape_catalog_read ON instruments FOR SELECT TO PUBLIC USING (true);
CREATE POLICY tape_catalog_fixture_insert ON instruments FOR INSERT TO tape_catalog_writer
    WITH CHECK (source='TAPE_FIXTURE' AND fictional AND synthetic_key IS NULL);
CREATE POLICY tape_catalog_fixture_update ON instruments FOR UPDATE TO tape_catalog_writer
    USING (source='TAPE_FIXTURE' AND fictional AND synthetic_key IS NULL)
    WITH CHECK (source='TAPE_FIXTURE' AND fictional AND synthetic_key IS NULL);
GRANT SELECT ON instruments TO tape_catalog_writer;
GRANT INSERT (id,source,source_key,exchange,symbol,name,isin,instrument_type,listing_status,tick_size_paise,synchronized_at,fictional),
    UPDATE (symbol,name,isin,instrument_type,listing_status,tick_size_paise,synchronized_at)
    ON instruments TO tape_catalog_writer;
