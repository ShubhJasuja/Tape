
# Stage 2E market data

The authenticated account shell has a dedicated Market data workspace. The Spring Boot MarketDataProvider contract serves catalog discovery, REST quotes, exchange status, candles, option contracts and chains, and streaming. UpstoxMarketData is the only adapter. The browser receives normalized data from Tape, never Upstox credentials or signed stream URLs. Successful responses prevent browser caching. Upstox data is unavailable by default.

## Permission and local configuration

Upstox documents token scope and instrument formats. Public documentation does not establish a license to use Upstox data in Tape. The authorization gate requires the local account profile, one specific Tape user UUID, a permission reference, and explicit capabilities. An operator must obtain and record written authorization before enabling each capability. The reference is an operator-supplied identifier for an agreement, not evidence that Tape verified it. Confirm the owner's identity, approved exchanges and segments, price delay, storage and retention, and separate rights for display, replay, virtual-fill computation, public access and export. The implementation has no shared-account or commercial configuration.

- Use Tape's normal PostgreSQL account setup and apply Flyway V6 as the migration role. Do not apply database grants for stored external data unless approved.
- Create a local Tape account, then obtain its user UUID through the local schema administrator. Use the user UUID, rather than a trading account UUID.
- Set UPSTOX_ANALYTICS_TOKEN in the local Spring Boot process environment. Generate the token through your own Upstox Developer Apps Analytics page once provider permission has been established. Keep it outside the repository, command-line arguments, browser and logs. Restart the server after rotating it. The provider documents one-year validity, and can revoke a token earlier.
- Set TAPE_MARKET_OWNER_ID, TAPE_MARKET_PERMISSION_REF and TAPE_MARKET_PERMISSIONS to the approved user, reference, and comma-separated capabilities. Valid entries are CATALOG, CATALOG_STORAGE, QUOTES, HISTORY, STREAM, DERIVATIVES, OPTION_CHAIN and WATCHLIST. Leave unapproved entries absent. F&O additionally requires DERIVATIVES. Search requires CATALOG and persistent metadata also requires CATALOG_STORAGE. Turning off the permission stops reads and writes through the API. A schema administrator handles any purge required by the agreement.
- Price capabilities (QUOTES, HISTORY, STREAM and OPTION_CHAIN) also require TAPE_MARKET_DISPLAY_DELAY=NONE. Use that setting only when the documented permission allows the covered data without a minimum display delay. Missing values and any other setting disable all four capabilities. Tape does not yet serve a delay-controlled feed or historical data embargoed by an agreement.
- Launch with profiles accounts,accounts-local on loopback. Do not proxy this workspace or forward the local port. Other Tape users see Disconnected and cannot use this credential. Demo runs independently.

Published references are the [Analytics Token](https://upstox.com/developer/api-documentation/analytics-token/), [instrument master](https://upstox.com/developer/api-documentation/instruments/), [instrument search](https://upstox.com/developer/api-documentation/instrument-search/), [terms](https://upstox.com/terms-of-use-and-privacy-policy/) and [SEBI educational-data circular](https://www.sebi.gov.in/legal/circulars/may-2026/norms-for-sharing-and-usage-of-price-data-for-educational-purposes_101293.html). This stage made no provider requests. If the contemplated use has a minimum data delay, leave the affected capabilities disabled until that delay can be enforced.

## Catalog and contracts

Search reads paginated Upstox results without downloading the beginning-of-day master. The model supports NSE/BSE equities, NSE indices, and NSE index or stock futures and CE/PE options. Keys are scoped to the provider and exchange segment. Metadata includes symbol, name, optional ISIN, underlying, expiry, strike in INR, side, lot size, tick size in provider paise, and listing status when supplied. Expired contracts are excluded from selectable option expiries. Absent or unrecognized trading status stays unknown.

V6 adds separate tables for personal provider watchlist keys and optional normalized provider metadata. Apply [market-data-runtime-grants.sql](market-data-runtime-grants.sql) for separately approved persistence. An approved operator may set TAPE_MARKET_CATALOG_SYNC_QUERY and optionally TAPE_MARKET_CATALOG_SYNC_SEGMENT to run a bounded search import at startup. It requires the catalog, durable metadata, and applicable segment permissions before contacting Upstox. It reads at most ten pages or fails before persistence, then applies the complete query result atomically. A stable internal UUID and provider key survive renames. A conflicting identity rolls back the import. A query is a subset, so omitted contracts are never deleted or marked inactive by inference. Remove the sync setting after the intended run. No raw master file, quote, candle or chain is persisted.

## Read-only market API

Routes below are relative to /api/v1/market-data. All require an authenticated session. The watchlist is the only user write, and uses CSRF-protected PUT and DELETE.

| Route | Inputs and result |
|---|---|
| GET /status | Configured, token required, or disconnected, with owner-scoped capabilities and intervals |
| GET /instruments | q, exchange, segment, optional expiry, page. A page of 20 provider instruments with nextPage when available |
| GET /instrument | key. A listing already stored with catalog permission |
| GET /indices | q, page. NSE index lookup |
| GET /futures | q, optional expiry, page. NSE futures lookup |
| GET /options | key of the NSE underlying and optional expiry. Eligible option contracts with lot sizes |
| GET /option-chain | key of the NSE underlying and expiry. Strike rows with independent nullable CE and PE records |
| GET /quote | key. Matching provider snapshot with exchange and receipt timestamps where available |
| GET /exchange-status | NSE or BSE. Provider-supplied session status |
| GET /candles | key, interval, optional from and to. Ascending validated OHLCV bars |
| GET /stream | key. Connection state and latest V3 LTPC update for the selected instrument |
| GET /watchlist | Current owner's provider keys |
| PUT /watchlist | JSON instrumentKey to pin |
| DELETE /watchlist | Query key to unpin |

## Prices, freshness and transport

Prices from REST and WebSocket are display-only decimal INR. Missing values remain absent. Quotes distinguish exchangeAt (last trade), providerAt (REST generation time or WebSocket currentTs), and receivedAt (Tape's clock). Only last-trade time is used for freshness. The workspace updates the stale indicator even for an unstreamed quote after 30 seconds. Quote age does not establish contractual feed delay. A disconnected stream discards its last tick. Option-chain responses have a receipt timestamp because the documented response supplies no exchange timestamp per row.

Spring Boot uses GET requests to fixed Upstox REST routes with a five-second end-to-end deadline and a 4 MiB body limit. Transient network, timeout and 5xx failures get one retry within the request budgets. Upstox 429 responses honor Retry-After in seconds or HTTP-date form within a 2–1,800 second bound. HTTP 401 reports a rejected credential and retains that status during a 60-second cooldown. HTTP 403 reports denied provider access separately. The adapter caps concurrent requests at four to allow the workspace's parallel reads and enforces process-wide limits of 15/sec, 400/min and 1,550/30 min. Upstox standard APIs publish 50/sec, 500/min and 2,000/30 min per API and user. Other clients sharing the token can consume its quota.

Streaming uses one upstream V3 connection and one selected instrument in LTPC mode. The signed WSS endpoint stays server-side and is restricted to Upstox hosts. The terminal polls Tape every two seconds while updates are active. Tape waits up to 12 seconds after opening a socket for a matching status or quote frame. Once established, a silence of 45 seconds triggers recovery. Each recovery obtains a fresh signed URL after bounded backoff and ignores callbacks from earlier attempts. Idle subscriptions expire. Multiple tabs share the connection and can replace each other's selection. Stream ticks update LTP and change where supplied. REST supplies the distinct OHLCV snapshot.

The chart offers daily, one-minute and five-minute bars for completed dates using Asia/Kolkata labels. Default date windows are 365, 7 and 28 calendar days respectively. The chart displays the latest 220 received bars. Data is filtered by request dates and timestamp, malformed OHLC bars are ignored, and conflicting duplicates fail. Tape preserves missing bars and does not distinguish exchange holidays, halts or illiquidity from provider gaps. Archived contracts require a separate provider endpoint and entitlement. The workspace filters expired dates in IST, including for a tab open across midnight.

See [Upstox full quotes](https://upstox.com/developer/api-documentation/get-full-market-quote-v3/), [exchange status](https://upstox.com/developer/api-documentation/get-market-status/), [historical candles](https://upstox.com/developer/api-documentation/v3/get-historical-candle-data/), [WebSocket V3](https://upstox.com/developer/api-documentation/v3/get-market-data-feed/), [option contracts](https://upstox.com/developer/api-documentation/get-option-contracts/), [option chains](https://upstox.com/developer/api-documentation/get-pc-option-chain/) and [rate limits](https://upstox.com/developer/api-documentation/rate-limiting/).

## Execution boundary

The PostgreSQL order engine, reservations, fee logic, matcher, reconciler, and nine synthetic models are unchanged. PostgreSQL V5 fixtures and their watchlists remain available in Demo. Previously the H2 path fetched quotes with the same environment token and could use them to fill virtual orders. Its feed is now disconnected. H2 can display existing live ledger history and cancel outstanding orders. New live orders and live matching have no provider-price path.

## Stage 2F verification and next step

Real connectivity remains unverified. No Upstox token or market permission was configured in the inspected environment, and no account-specific grant permitting Tape's contemplated display or storage was established. The equity, index, option expiry, historical request and stream checks used contract fixtures. No upstream payloads or signed URLs were collected. Obtain the written scope for those exact calls, including exchange, segment, audience, display delay and retention. Install the credential in the server environment and record only sanitized statuses and field presence during a permitted pilot. Simulated execution, replay, storage and redistribution each need their own approval. If the pilot requires a delay, add and test an appropriate data embargo before using real data.

## Derivatives readiness

The current matcher reserves shares for sells, computes gross as INR price times shares, and projects nonnegative positions and cash postings tied to executions. Provider contract records are separate from trading instruments. Derivatives require explicit contract specifications, signed positions, and new economic events before any execution source is connected.

- Version the exchange contract key, lot multiplier, tick size, underlying, expiry cutoff, settlement type and final settlement price source. Validate lots and tick increments server-side and keep the effective specification with each execution.
- Long options reserve premium plus fees and post the premium at execution. Shorts post the premium credit and reserve margin separately. Recompute collateral for adverse moves and expiry obligations, with deterministic handling for insufficient margin.
- Futures reserve margin and post daily variation margin. Reconcile realized versus unrealized P&L for partial closes, daily marks, and final settlement without double-counting prior cash postings.
- Keep expiry, exercise, assignment and settlement idempotent and auditable. Model index cash settlement separately from stock delivery and the associated funding and share obligations.
- Multi-leg strategies require atomic admission and margin calculation, explicit leg fills, and policy for partial fills and offsets. Keep account mode and quote provenance explicit. Start with authorized read-only data validation, then design an isolated replay or derivatives ledger before implementing a derivative order path.

See [NSE contract specifications](https://www.nseindia.com/static/products-services/equity-derivatives-contract-specifications), [NSE Clearing settlement](https://www.nseclearing.in/clearing-settlement/equity-derivatives/settlement-mechanism), [NSE individual securities](https://www.nseindia.com/static/products-services/equity-derivatives-individual-securities), and [Upstox expired instruments](https://upstox.com/developer/api-documentation/expired-instruments/).
