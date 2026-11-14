# Indian equity catalog

Tape stores instrument metadata in PostgreSQL. The authenticated Demo market watch searches the catalog by listing symbol, company name, or ISIN, with NSE/BSE and equity/SME filters. The 96 bundled discovery listings are fictional. Their FICT symbols and ZZ sample identifiers do not represent exchange listings. The nine existing instruments retain their synthetic pricing models. Demo charts use synthetic candles. The separate [Market data workspace](market-data.md) requires explicit permission for provider history.

## Sources and permission

[Upstox BOD documentation](https://upstox.com/developer/api-documentation/instruments/) describes NSE/BSE JSON files, stable instrument keys, and a separate suspended list. [NSE’s securities lists](https://www.nseindia.com/static/market-data/securities-available-for-trading) include equity, SME, and symbol and company name changes. File availability does not establish Tape’s rights to ingest, retain, display, or redistribute those records. Review the [NSE website terms](https://www.nseindia.com/static/nse-terms-of-use) and [data policy](https://www.nseindia.com/static/market-data/nse-data-policy) and obtain written authorization for the intended use before enabling a real adapter. This build reads no external catalog files and performs no network downloads.

## Identity and execution

Flyway V5 adds source identity, optional ISIN and tick size, classification, listing status, synchronization timestamp and fictional/price capability fields. The nine mapped listings keep their internal UUIDs and receive separate stable synthetic keys. All existing foreign keys from orders, executions, positions and watchlists stay valid. Order requests retain the originally submitted symbol for idempotent retries, even if the displayed symbol changes. Market and resting execution require an active, explicitly mapped synthetic listing. Inactive mapped instruments keep their synthetic valuation for existing holdings, but cannot accept or match orders. Existing open orders stay reserved and can be cancelled. The loopback H2 terminal retains its curated list for Demo.

The prior nine-instrument assumption covered legacy quote batches, candle and research lookup, legacy order validation, the authenticated market response and watchlist, PostgreSQL order validation, matching, portfolio marking, the React market selector, and matcher quote-health sampling. Legacy paths and synthetic-feed health remain tied to the nine configured price models by design. Discovery is independent of those models.

## Synchronization

A `CatalogFeed` returns a normalized snapshot. The bundled importer accepts only `TAPE_FIXTURE`, fictional names and symbols, and optional fictional ZZ identifiers. Files are capped at 4 MiB and snapshots at 10,000 entries. Use a strictly increasing UTC `asOf` with millisecond precision. Duplicate keys or exchange/symbol pairs fail before changes. PostgreSQL serializes imports per source, ignores same-time or older snapshots, and applies the whole snapshot in one transaction. Source key and exchange identify a listing across renames. An incompatible ISIN or a symbol collision fails the import. Explicit delistings are retained. A complete snapshot inactivates omitted active rows from that source. A partial snapshot leaves omitted rows alone. There are no deletes and no importer writes to the synthetic capability.

For a local sync, apply Flyway V5 first. Create a dedicated `tape_catalog_writer` LOGIN role with no role memberships or ownership, then run [catalog-runtime-grants.sql](catalog-runtime-grants.sql) as the schema owner. Configure `TAPE_CATALOG_URL`, `TAPE_CATALOG_USER`, and `TAPE_CATALOG_PASSWORD` in the environment. Run the importer as a separate non-web process:

```powershell
java -Duser.timezone=UTC -jar target/catalog/tape-1.0.0.jar --spring.profiles.active=catalog-import --tape.catalog.file=path/to/fixture.json
```

Example partial fixture, with an illustrative timestamp:

```json
{
  "source": "TAPE_FIXTURE",
  "asOf": "2026-01-01T00:00:00Z",
  "complete": false,
  "instruments": [
    {"sourceKey":"example-1","exchange":"NSE","symbol":"FICTEXAMPLE","name":"Example Systems (fictional)","instrumentType":"EQUITY","status":"ACTIVE"}
  ]
}
```

## API

All routes use `/api/v1/accounts/{accountId}` and require the owning authenticated Demo account. Normal session and CSRF rules apply.

| Method and suffix | Contract |
|---|---|
| GET `/instruments` | Parameters `q`, `exchange`, `type`, `pinned`, `offset`, and `limit`. Returns `instruments` and `nextOffset` when more results exist. Maximum page size 100. |
| GET `/instruments/{id}` | Full listing metadata by stable UUID. |
| PUT `/watchlist` | Send `pinned` and either `instrumentId` or the existing `symbol` field. UUID pins remain stable across renames. Active metadata-only listings may be pinned. Existing inactive pins remain visible and removable. |
| GET `/market` | Compatibility response for the synthetic price subset and the account owner’s watchlist. |
| GET `/candles` | Synthetic candles for active mapped instruments, empty for metadata-only listings. |
| POST `/orders` | Metadata-only or inactive instruments return 409 with `INSTRUMENT_NOT_TRADABLE`. |

The listing response separates `demoTradable`, `priceAvailability`, and `historicalAvailability`. Search is case-insensitive with literal wildcard handling and stable sorting. Offset pages can shift if the catalog changes between requests.

Next, obtain provider permission and implement a reviewed adapter with snapshot completeness validation and source-specific identity reconciliation. Then add licensed historical replay with separate timestamps, provenance, and execution policy.
