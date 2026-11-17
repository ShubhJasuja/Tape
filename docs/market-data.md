
# Market data terminal

The authenticated account shell has a dedicated Market data workspace. The Spring Boot MarketDataProvider contract serves catalog discovery, REST quotes, exchange status, candles, option contracts and chains, and streaming. UpstoxMarketData is the only adapter. The browser receives normalized data from Tape, never Upstox credentials or signed stream URLs. Successful responses prevent browser caching. Upstox data is unavailable by default.

## Permission and local configuration

Upstox documents token scope and instrument formats. Public documentation does not establish a license to use Upstox data in Tape. The authorization gate requires the local account profile, one specific Tape user UUID, a permission reference, and explicit capabilities. An operator must obtain and record written authorization before enabling each capability. The reference is an operator-supplied identifier for an agreement, not evidence that Tape verified it. Confirm the owner's identity, approved exchanges and segments, price delay, storage and retention, and separate rights for display, replay, virtual-fill computation, public access and export. The implementation has no shared-account or commercial configuration.

- Use Tape's normal PostgreSQL account setup and apply Flyway through V10 as the migration role. Do not apply database grants for stored external data unless approved.
- Create a local Tape account, then obtain its user UUID through the local schema administrator. Use the user UUID, rather than a trading account UUID.
- Set UPSTOX_ANALYTICS_TOKEN in the local Spring Boot process environment. Generate the token through your own Upstox Developer Apps Analytics page once provider permission has been established. Keep it outside the repository, command-line arguments, browser and logs. Restart the server after rotating it. The provider documents one-year validity, and can revoke a token earlier.
- Set TAPE_MARKET_OWNER_ID, TAPE_MARKET_PERMISSION_REF and TAPE_MARKET_PERMISSIONS to the approved user, reference, and comma-separated capabilities. Valid entries are CATALOG, CATALOG_STORAGE, QUOTES, HISTORY, INTRADAY, STREAM, DERIVATIVES, OPTION_CHAIN, CALENDAR and WATCHLIST. Leave unapproved entries absent. F&O additionally requires DERIVATIVES. Search requires CATALOG and persistent metadata also requires CATALOG_STORAGE. Turning off the permission stops reads and writes through the API. A schema administrator handles any purge required by the agreement.
- Set TAPE_MARKET_EXCHANGES to the explicitly approved NSE and/or BSE exchanges. Existing owner configurations need this scope after upgrading. An absent scope disables the provider. An all-exchange search uses the approved exchange scope, and NSE derivatives additionally require NSE in that scope.
- Set TAPE_MARKET_DISPLAY_DELAY=NONE only for approved access with no contractual delay. A positive ISO 8601 duration such as P30D allows an explicitly approved HISTORY capability through the last complete IST day before the embargo boundary. Tape rejects newer date ranges before fetching and filters returned rows to the approved range. QUOTES, INTRADAY, STREAM and OPTION_CHAIN remain disabled in this mode. Missing or invalid delay settings disable all price capabilities. This setting supplies no permissions by itself.
- Launch with profiles accounts,accounts-local on loopback. Do not proxy this workspace or forward the local port. Other Tape users see Disconnected and cannot use this credential. Demo runs independently.

Stage 2J used local contract fixtures for provider validation. Real connectivity and account-specific authorization remain unverified. The SEBI educational-data circular sets a 30-day lag for qualifying education and awareness use, with an agreement and other conditions. Confirm applicability and terms for Tape before commissioning the integration. See the [Analytics Token](https://upstox.com/developer/api-documentation/analytics-token/), [instrument master](https://upstox.com/developer/api-documentation/instruments/), [instrument search](https://upstox.com/developer/api-documentation/instrument-search/), [Upstox terms](https://upstox.com/terms-of-use-and-privacy-policy/) and [SEBI educational-data circular](https://www.sebi.gov.in/sebi_data/attachdocs/may-2026/1778242522289.pdf).

## Catalog and contracts

Search reads paginated Upstox results without downloading the beginning-of-day master. The model supports NSE/BSE equities and indices, and NSE index or stock futures and CE/PE options. Keys are scoped to the provider and exchange segment. Metadata includes symbol, name, optional ISIN, underlying, expiry, strike in INR, side, lot size, tick size in provider paise, and listing status when supplied. The adapter also reads quantity multiplier, minimum lot, freeze quantity and weekly classification when supplied. Expired contracts are excluded from selectable option expiries. Absent or unrecognized trading status stays unknown.

V6 adds separate tables for personal provider watchlist keys and optional normalized provider metadata. Apply [market-data-runtime-grants.sql](market-data-runtime-grants.sql) for separately approved persistence. An approved operator may set TAPE_MARKET_CATALOG_SYNC_QUERY and optionally TAPE_MARKET_CATALOG_SYNC_SEGMENT to run a bounded search import at startup. It requires the catalog, durable metadata, and applicable segment permissions before contacting Upstox. It reads at most ten pages or fails before persistence, then applies the complete query result atomically. A stable internal UUID and provider key survive renames. A conflicting identity rolls back the import. A query is a subset, so omitted contracts are never deleted or marked inactive by inference. Remove the sync setting after the intended run. No raw master file, quote, candle or chain is persisted.

V10 creates named provider watchlists. Existing pins move into Default, and the composite foreign key binds each pin to its user's list. The migration also accepts BSE index keys in the watchlist and catalog tables. Reapply the separately approved runtime grants after migrating. A rollback to older code requires a database recovery plan because the former insert contract cannot create the Default parent for new users and the earlier schema cannot represent additional watchlists. Export or back up the watchlist data before a manual downgrade.

## Read-only market API

Routes below are relative to /api/v1/market-data. All require an authenticated session. Watchlist POST, PUT and DELETE require CSRF protection.

| Route | Inputs and result |
|---|---|
| GET /status | Configured, token required, or disconnected, with owner-scoped capabilities, exchanges, intervals and display delay |
| GET /instruments | q, exchange, segment, optional expiry, page. A page of 20 provider instruments with nextPage when available |
| GET /instrument | key. A listing already stored with catalog permission |
| GET /indices | q, page and exchange, default NSE. NSE/BSE index lookup |
| GET /futures | q, optional expiry, page. NSE futures lookup |
| GET /options | key of the NSE underlying and optional expiry. Eligible option contracts with lot sizes |
| GET /option-chain | key of the NSE underlying and expiry. Strike rows with nullable CE/PE records, premiums, bid/ask and sizes, OI, prior OI, volume, close, IV and provider Greeks. Underlying spot and PCR are nullable |
| GET /quote | key. Matching provider snapshot with exchange and receipt timestamps where available |
| GET /exchange-status | NSE or BSE. Provider-supplied session status |
| GET /market-session | exchange (NSE, BSE or NFO). Today's IST date, schedule windows, phase and optional holiday |
| GET /candles | key, interval, optional from and to. Ascending validated historical OHLCV bars constrained by the display delay |
| GET /intraday | key, interval. Current IST session bars |
| GET /stream | key. Connection state and latest V3 LTPC update for one instrument |
| GET /streams | keys, comma separated, and optional tab UUID. Connection state and quotes scoped to that tab's authorized keys. Up to 16 keys per request |
| GET /watchlists | Current owner's list names, including Default |
| POST /watchlists | JSON name, up to 40 characters. Creates a named list |
| DELETE /watchlists | Query name. Removes a named list and its pins |
| GET /watchlist | Optional query name, default Default. Current owner's provider keys in that list |
| PUT /watchlist | Optional query name and JSON instrumentKey to pin |
| DELETE /watchlist | Optional query name and query key to unpin |

## Prices, freshness and transport

Prices from REST and WebSocket are display-only decimal INR. Missing values remain absent. Quotes distinguish exchangeAt (last trade), providerAt (REST generation time or WebSocket currentTs), and receivedAt (Tape's clock). Only last-trade time is used for freshness. The workspace updates the stale indicator even for an unstreamed quote after 30 seconds. Quote age does not establish contractual feed delay. A disconnected stream discards its last tick. Option-chain responses have a receipt timestamp because the documented response supplies no exchange timestamp per row.

Spring Boot uses GET requests to fixed Upstox REST routes with a five-second end-to-end deadline and a 4 MiB body limit. Transient network, timeout and 5xx failures get one retry within the request budgets. Upstox 429 responses honor Retry-After in seconds or HTTP-date form within a 2–1,800 second bound. HTTP 401 reports a rejected credential and retains that status during a 60-second cooldown. HTTP 403 reports denied provider access separately. The adapter caps concurrent requests at eight to allow the workspace's parallel reads and enforces process-wide limits of 15/sec, 400/min and 1,550/30 min. Upstox standard APIs publish 50/sec, 500/min and 2,000/30 min per API and user. Other clients sharing the token can consume its quota.

Streaming uses one upstream V3 connection in LTPC mode. Each authenticated user, server session and tab UUID identifies a lease for its own selected listing and watchlist keys. The connection carries the union of active leases, and each response contains only the requested keys. Leases expire after 15 seconds without a poll. Legacy requests with no tab UUID share a legacy lease within the same session. Limits are 8 active leases per user, 128 per process, 64 distinct upstream keys, and 120 market requests per user per process per minute. The signed WSS endpoint stays server-side and is restricted to Upstox hosts. Tape polls every two seconds while active and visible, waits up to 12 seconds for an initial status or quote frame, and reconnects after 45 seconds of silence. Reconnect uses a fresh signed URL and ignores prior socket callbacks. Stream ticks update LTP and change. REST supplies the OHLCV snapshot.

The Upstox adapter remains restricted to one approved local owner. The in-memory registry handles only tabs on that owner’s node. A PostgreSQL advisory lock grants one node access to the upstream stream, and another node returns MARKET_FEED_BUSY instead of opening a duplicate connection. Route the owner’s local workspace to the active node. Locks use one dedicated database connection with an open transaction until the last lease ends. Session invalidation removes its leases immediately. A one-second sweep removes expired or invalid sessions without waiting for another browser poll, unsubscribes unused keys and closes an empty stream. Connection loss can interrupt the feed and the next successful request reacquires ownership. This mechanism supplies no shared public entitlement or guaranteed cross-node failover during a network partition. An eventual licensed shared feed requires separate credentials and a gateway that partitions fan-out by grant. See [public launch readiness](public-launch.md) for the licensing and operating requirements.

The chart offers daily, one-minute and five-minute bars for completed dates using Asia/Kolkata labels. Default date windows are 365, 7 and 28 calendar days respectively. The chart displays the latest 220 historical bars. Current-session candles use the intraday endpoint and refresh every 30 seconds in the visible workspace. The latest current-session candle can still be forming. Data is filtered by request dates and timestamp, malformed OHLC bars are ignored, conflicting duplicates fail, and gaps remain visible in the input series. The market-session endpoint uses Upstox calendar data for the selected segment. It reports an unknown schedule when timings are missing. Its calendar does not affect the separate practice scheduler. Archived contracts require a separate provider endpoint and entitlement. The workspace filters expired dates in IST, including for a tab open across midnight.

See [Upstox full quotes](https://upstox.com/developer/api-documentation/get-full-market-quote-v3/), [exchange status](https://upstox.com/developer/api-documentation/get-market-status/), [historical candles](https://upstox.com/developer/api-documentation/v3/get-historical-candle-data/), [intraday candles](https://upstox.com/developer/api-documentation/v3/get-intra-day-candle-data/), [market holidays](https://upstox.com/developer/api-documentation/get-market-holidays/), [market timings](https://upstox.com/developer/api-documentation/get-market-timings/), [WebSocket V3](https://upstox.com/developer/api-documentation/v3/get-market-data-feed/), [option contracts](https://upstox.com/developer/api-documentation/get-option-contracts/), [option chains](https://upstox.com/developer/api-documentation/get-pc-option-chain/) and [rate limits](https://upstox.com/developer/api-documentation/rate-limiting/).

## Execution boundary

The PostgreSQL order engine, reservations, fee logic, matcher, reconciler, and nine synthetic models are unchanged. PostgreSQL V5 fixtures and their watchlists remain available in Demo. Previously the H2 path fetched quotes with the same environment token and could use them to fill virtual orders. Its feed is now disconnected. H2 can display existing live ledger history and cancel outstanding orders. New live orders and live matching have no provider-price path.

## Connectivity and next step

Obtain written scope for the planned calls, including exchange, segment, audience, display delay and retention. Install the credential in the server environment and record only sanitized statuses and field presence during a permitted pilot. Simulated execution, replay, storage and redistribution each need their own approval. Tape's approved delayed-history route can serve an embargoed pilot once the actual grant is established.

## Derivatives readiness

The current matcher reserves shares for sells, computes gross as INR price times shares, and projects nonnegative positions and cash postings tied to executions. Provider contract records are separate from trading instruments. Derivatives require explicit contract specifications, signed positions, and new economic events before any execution source is connected.

- Version the exchange contract key, lot multiplier, tick size, underlying, expiry cutoff, settlement type and final settlement price source. Validate lots and tick increments server-side and keep the effective specification with each execution.
- Long options reserve premium plus fees and post the premium at execution. Shorts post the premium credit and reserve margin separately. Recompute collateral for adverse moves and expiry obligations, with deterministic handling for insufficient margin.
- Futures reserve margin and post daily variation margin. Reconcile realized versus unrealized P&L for partial closes, daily marks, and final settlement without double-counting prior cash postings.
- Keep expiry, exercise, assignment and settlement idempotent and auditable. Model index cash settlement separately from stock delivery and the associated funding and share obligations.
- Multi-leg strategies require atomic admission and margin calculation, explicit leg fills, and policy for partial fills and offsets. Keep account mode and quote provenance explicit. Start with authorized read-only data validation, then design an isolated replay or derivatives ledger before implementing a derivative order path.

See [NSE contract specifications](https://www.nseindia.com/static/products-services/equity-derivatives-contract-specifications), [NSE Clearing settlement](https://www.nseclearing.in/clearing-settlement/equity-derivatives/settlement-mechanism), [NSE individual securities](https://www.nseindia.com/static/products-services/equity-derivatives-individual-securities), and [Upstox expired instruments](https://upstox.com/developer/api-documentation/expired-instruments/).
