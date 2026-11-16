# Stage 2H options practice

The authenticated Options practice workspace has its own virtual cash, orders, positions and journal. The demo account owns this book. On its first accepted order, Tape posts ₹10,00,000 of virtual funding and records the order in the same transaction. Existing equity accounts, wallet balances, positions, executions and H2 storage keep their existing behavior.

## Run locally

Use the `accounts,accounts-local` profiles and a local PostgreSQL 17 instance. Apply Flyway through V8 using the migration identity, then apply `options-runtime-grants.sql` as the schema owner after the existing accounts and synthetic trading grants. Set `TAPE_PRACTICE_OPTIONS_ENABLED=true` on the loopback server. The feature defaults to off. `TAPE_PRACTICE_OPTIONS_FEE_PAISE` defaults to 200 (₹2) and allows 1–10,000 paise. The configured fee is captured when an order is accepted. The demo account must have trading enabled. The options feature flag is separate from the equity trading feature flag.

Start Java with `-Duser.timezone=UTC` before `-jar` on Windows. Some PostgreSQL images reject the `Asia/Calcutta` alias that the PostgreSQL JDBC driver can send for the Windows timezone. The UI still displays contract times in India Standard Time.

V8 adds the practice order table and its indexes, links fills to their account, contract and specification, and adds a fee column with a zero default to the existing immutable journal. V8 adds a finite practice chain using a dedicated source namespace. Later specifications and fixture expiries can be appended through migrations. Existing migrations remain intact. To pause new orders and matching, set `TAPE_PRACTICE_OPTIONS_SUBMISSIONS=false` while keeping `TAPE_PRACTICE_OPTIONS_ENABLED=true`. Reads, retries, cancellation and expiry keep running. Drain orders and positions before disabling the feature or rolling back application code. Keep the migrated tables and journal. Coordinate schema changes with the owner and retain a verified backup.

## Execution and accounting

- The chain follows the existing calls/strikes/puts layout and reads from the isolated practice catalog. The Practice Index is a bounded deterministic series from ₹900 to ₹1,100. Premiums are tick-aligned synthetic marks derived from intrinsic value and a fixed premium component. The workspace never posts an exchange quote or calls Upstox for execution. It uses the simulated pricing schedule at all times, including outside exchange hours.
- Market orders fill at the server mark during processing. A limit fills at that mark if its threshold is crossed. A remaining order is evaluated by a polling worker. There is one open order per account and contract. Order fills are all-or-none. Users can partially close existing positions by submitting an opposite order for fewer lots.
- The order accepts an integer number of lots and a limit in integer paise per unit. The book uses versioned lot size, multiplier, tick size, position limits and a margin amount per short lot. A changed specification can invalidate an opening order. Existing exposure can be reduced under the position's original specification.
- Options buys debit premium and options sells credit premium. Every fill charges the saved fee. Realized P&L includes fees as incurred and uses proportional remaining premium basis when a position closes. Unrealized P&L uses the current practice mark and excludes estimated future fees. Margin and reserves are shown separately from virtual cash.
- Short openings must cover configured collateral without relying on the premium for newly opened lots. The fixture's versioned short collateral must cover its maximum cash payout across the bounded practice series. This is illustrative risk collateral. There is no exchange-certified margin calculation. A resting buy reserves its maximum premium and fee, adjusted for margin released by covering a short. A resting sell reserves the applicable increase in collateral and fees before counting any premium proceeds.
- Ownership and `DEMO` mode are checked under the existing PostgreSQL account lock. The same lock serializes new orders, cancellation, fills and settlement. Durable request IDs are scoped to one account. The same payload returns the recorded order and a changed payload using its key is rejected. Release, fill, fee posting and order completion share a transaction. The matcher uses a database savepoint so a rejected fill rolls back before releasing that order's reserve and recording a rejection.
- After expiry the worker expires any resting order and settles open positions at the deterministic underlying price for the contract's expiry instant. Cash flow is signed intrinsic value × units and margin is released. Database uniqueness prevents a duplicate settlement. Work is derived from stored open orders and positions, so a worker restart can resume it. The reconciliation check replays the book and compares posted reservations to open orders and recorded fills to their tickets.

## API

- `GET /api/v1/accounts/{id}/options?offset=0` returns the practice chain, balances, positions, 25 order history rows and up to 25 recent settlements.
- `POST /api/v1/accounts/{id}/options/orders` accepts `requestId`, `contractId`, `specVersion`, `side`, `lots` and optional `limitPaise`. Omit the limit for a market order.
- `DELETE /api/v1/accounts/{id}/options/orders/{orderId}` cancels an open order and returns its resulting state. Repeating the cancellation returns the existing terminal state.

Writes require the current session's CSRF token. Server responses carry sanitized error codes. The browser keeps an unconfirmed submission's request ID and payload in per-account session storage for retries.

## Remaining scope

The supported contracts are cash-settled practice CE/PE contracts. Physical delivery, futures orders, real exchange calendars, order-book liquidity, partial fills, portfolio offsets, forced liquidation and provider-based execution need their own design and verification. Test using Java 21, Docker and `./mvnw verify -Dtape.postgres.tests=true`. The suite uses disposable PostgreSQL and no external market-data credentials.
