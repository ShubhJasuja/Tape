# Stage 2C synthetic trading design

The accounts profile uses a separate PostgreSQL engine. The legacy H2 terminal retains its own controller, scheduler, provider integrations and storage. The authenticated path uses the existing instrument catalog and an epoch-based synthetic feed. It never calls a real order API.

Every command uses one database transaction. It locks the owned trading account first, then its wallet, before reading or changing an order or a position. The matcher takes the same locks and rereads each candidate order after locking. Different accounts can proceed independently. Reads with multiple queries use a repeatable-read snapshot. The account lock also serializes retries and protects positions that do not exist yet. Conditional updates allow only OPEN orders to transition, and a synthetic full fill has one execution per order.

Money uses bounded integer paise and checked arithmetic. An open buy reserves trigger notional plus the fee. An open sell reserves shares. Cancellation releases the corresponding reservation. A fill releases its own reservation before testing the actual fill cost. A stop gap can reject an order if it needs unavailable funds. The fee is 5 basis points rounded up. Buys add fees to cost basis. Sells allocate average cost by truncating the proportional cost to paise and preserve the rounding remainder until the final sale. Gross cash, fee postings, execution and projections commit together. A wallet must equal the sum of cash postings, and positions must match the ordered execution replay. Open orders explain reservations.

Market orders use the current server quote. Limit buys cross at or below their trigger and limit sells at or above it. Stop buys trigger at or above their trigger and stop sells at or below it. Resting orders match at the observed price. Price ticks are stable for the same symbol and two-second UTC bucket across restarts. The matcher samples current ticks every two seconds, with older orders evaluated first within each account. Quotes must have a positive bounded price, may not be future-dated, and expire after six seconds. Resting orders require a tick at or after submission. Missed ticks during downtime are skipped. There is no order book, exchange calendar, partial fill, broker connection or live-data execution.

Order identity is scoped to the account and a request UUID. Same-key same-payload retries return the same durable order with its current state. A conflict returns 409. A browser preserves a pending account-scoped request across reloads and only retries that exact request until the result is known.

## Enable a local Demo workspace

Apply Flyway V4 with the schema owner. Run [accounts-runtime-grants.sql](accounts-runtime-grants.sql) if it has not already been applied, then [trading-runtime-grants.sql](trading-runtime-grants.sql). Start the accounts workspace using the [local profile instructions](authentication.md#profiles-and-local-setup) with `TAPE_SYNTHETIC_TRADING_ENABLED=true`. The default is false. Keep one instance with the matcher enabled. Additional instances must set `TAPE_SYNTHETIC_MATCHER_ENABLED=false` until worker ownership is designed.

V4 enables eligibility on existing DEMO accounts, seeds the synthetic instrument catalog, adds a rejection code and an open-order index, and enforces one synthetic execution per order. The ownership-scoped lock function uses a fixed search path and is granted only to the runtime role. It lets the engine acquire a row lock without granting writes to account metadata. See [PostgreSQL row locks](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS). V1–V3 and the append-only triggers remain intact. The runtime grants permit required inserts and narrowly scoped projection and order-state updates. They do not grant ledger or execution mutation.

## Trading API

Use `/api/v1/accounts/{accountId}` as the prefix. All routes require the authenticated owner and an enabled DEMO account. Submit, cancel, and pin operations require the session's CSRF header.

| Method | Suffix | Result |
|---|---|---|
| POST | `/orders` | Submit JSON with `idempotencyKey`, `symbol` (e.g. NSE:RELIANCE), `side` (BUY or SELL), `kind` (MARKET, LIMIT or STOP), `quantity`, and a rupee-denominated `trigger` for resting orders. Returns 200 and the order, including a durable REJECTED state for insufficient funds, shares, or a projection limit. |
| GET | `/orders?offset=0&limit=25` | Orders newest first and `nextOffset` when more exist. Limit is 1–100. |
| GET | `/orders/{orderId}` | Order state, fill, fee, quote timestamp/source, and rejection reason when applicable. |
| GET | `/order-requests/{key}` | Look up an earlier submission by its account-scoped request key. |
| DELETE | `/orders/{orderId}` | Cancel an OPEN order and release its reservation. Repeat cancellation returns the cancelled order. Other terminal states return 409. |
| GET | `/wallet` | Total, reserved, and available cash in paise. |
| GET | `/portfolio` | Wallet, positions, reserved and available shares, cost basis, equity, and realized and unrealized P&L. |
| GET | `/market` | Synthetic quotes, instruments, and the owner's watchlist. |
| GET | `/candles?symbol=NSE:RELIANCE` | Synthetic daily candles. |
| PUT | `/watchlist` | Pin or unpin JSON `{symbol,pinned}`. Returns 204. |

Unauthenticated reads return 401. Missing CSRF, or an owned account whose trading is disabled, returns 403. Missing and foreign resources return 404. Invalid tickets return 400, conflicting keys return 409, and an unavailable quote returns 503. Trading domain errors include a stable `code` in the problem response. The service derives fills from its quote provider. A client quote field has no effect.

A definitive rejection is an order result and is replayable. A request that fails validation or rolls back before commit has no durable order. The client retains an ambiguous ticket in browser storage without credentials, tokens or session IDs. It uses a per-account browser lock, first looks up the saved key, and retries only the saved ticket if no order is found. Clearing browser storage or using a different device removes this local recovery aid. The same server key remains safe to replay.

## Operations and remaining work

Reconciliation currently runs through a service method and integration tests. It compares cash with postings, quantities and cost with execution replay, and reservations with open orders. It reports drift without altering records. Portfolio reads replay executions for realized P&L, and the single matcher scans open orders. These paths need operational metrics, bounded batches and load checks before shared deployment. Offset pagination may shift as new orders arrive.

The PostgreSQL workspace includes synthetic charts and owner-scoped watchlists. Research and external feeds remain in the separate legacy terminal. There is no historical H2 import. To disable new PostgreSQL orders and matching, turn off the trading flag and retain PostgreSQL data. Existing PostgreSQL reservations remain until processing resumes. Take a validated PostgreSQL backup before migration or rollback; do not reverse schema changes by editing applied migrations. Stage 2D should add reconciliation monitoring and rehearse any import before a coordinated cutover.
