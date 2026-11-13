# Stage 2D-A operations

This stage applies to the PostgreSQL `accounts` profile. The loopback H2 terminal, external providers and Flyway V1–V4 are unchanged. There is no additional migration or runtime grant.

## Reconciliation

By default the worker checks up to 50 DEMO accounts every minute, including accounts temporarily disabled for trading. Set `TAPE_RECONCILIATION_SCHEDULED=false` to disable automatic runs. Adjust `TAPE_RECONCILIATION_BATCH_SIZE` (1–1000) and `TAPE_RECONCILIATION_DELAY_MS` (minimum 1000). It walks account IDs with a cursor and starts another scan after the end. A restart starts at the beginning.

Each account is checked in a PostgreSQL read-only, repeatable-read transaction with a five-second statement timeout. No trading account or wallet write locks are taken. The checks cover:

- One opening credit of 100,000,000 paise and wallet cash equal to net postings.
- Open buy and sell orders explain cash and share reservations.
- Replaying immutable executions in sequence reconstructs share quantities and remaining average-cost basis.
- Replayed realized P&L equals net ledger cash plus remaining position cost less opening funding. This identity assumes the current funding and trade posting types.
- A filled order has one execution. Other statuses have none. Executions match order quantity, trigger direction, fee rules and synthetic quote provenance. Each execution has the expected gross posting and fee posting.

The worker reports mismatches and continues to the next account. It never repairs a projection, modifies ledger history or suspends trading. Logs show check names, a correlation ID and a truncated SHA-256 reference of the account UUID. An authorized operator can correlate that reference by hashing a known UUID. Keep credentials, full identifiers and record values out of log searches and incident tickets.

## Matching

One instance runs the matcher. It takes at most `TAPE_MATCH_BATCH_SIZE` candidates per tick (default 100, range 1–1000) and waits `TAPE_MATCH_DELAY_MS` after each pass (default 2000, minimum 1000). Each pass advances a keyset cursor in account, creation-time and order-ID order. An empty page resets the cursor. A held database lock or a failing order does not indefinitely block later orders. Each match has a one-second lock timeout and five-second statement timeout. Failures are revisited on a later scan, without an immediate retry. The worker refuses concurrent invocations in the same process and still uses the original database locks and conditional order transitions.

Restart starts a fresh scan of open orders. Quotes come from the current two-second synthetic bucket. The matcher skips missed ticks during downtime and may skip a transient crossing between visits during a backlog. There is no historical catch-up, distributed worker ownership or replica failover.

## Health, metrics and manual reconciliation

Set `TAPE_OPS_TOKEN` to a randomly generated 32–128 character URL-safe token from a secret store or local environment. With no token configured, these routes are denied. Connect directly to the loopback listener and supply the token as an `Authorization: Bearer …` header. A Tape account session grants no operations access. Do not forward `/actuator/**` or `/internal/ops/**` through a public proxy. A proxy on the same machine could otherwise make a request appear to originate on loopback. Treat the token as an operator credential and rotate it by changing the secret and restarting the process.

| Route | Meaning |
|---|---|
| GET `/actuator/health/liveness` | Spring application liveness, independent of PostgreSQL. |
| GET `/actuator/health/readiness` | Spring readiness and PostgreSQL connectivity. Returns 503 when not ready. |
| GET `/actuator/metrics`, `/actuator/metrics/{name}` | Micrometer catalog and measurements. The local registry resets on restart. |
| POST `/internal/ops/reconcile` | Check the next account page immediately. Returns aggregate counts only. 200 means the page ran, including possible mismatches. 409 means a scan is running. 503 means one or more checks failed to run. Repeat until `scanComplete` is true. |

Only health and metrics are exposed by Actuator in accounts mode. Probe responses omit component details and JMX exposure is disabled. Trading request logs use ECS JSON, a generated correlation ID and the response status. Worker logs contain a generated correlation ID, a hashed reference when applicable and the exception class. They omit exception payloads, headers, query strings, session cookies, passwords, and tokens.

## Suggested alert thresholds

| Signal | Suggested threshold | First response |
|---|---|---|
| `tape.reconciliation.accounts` outcome `mismatch` | Any increase | Review the check names and reconcile the affected account using an authorized read-only connection. |
| `tape.matcher.failures` outcome `order` or `batch` | At least 3 failures in 5 minutes | Check database readiness, lock waits and the referenced orders. |
| `tape.synthetic.quote.age` in seconds | Over 6 seconds for 3 polls, or missing/nonfinite while matcher is enabled | Check that the matcher is running and the host clock is synchronized. |
| Readiness HTTP status | 503 for 30 seconds | Check database availability and the connection pool. Liveness should remain up for a database outage. |
| `tape.orders.submissions` outcome `error` | Over 2% for 5 minutes, with at least 20 submissions | Check database readiness and correlate error logs. Invalid tickets and ordinary trading rejections are separate outcomes. |
| `tape.matcher.duration`, `tape.reconciliation.duration`, `tape.orders.submission.duration` | Review sustained growth or passes exceeding the configured delay | Inspect backlog, statement timeouts and query performance. |

`tape.orders.executions` counts fills only after commit. `tape.synthetic.quote.samples` counts fresh and stale matcher samples. Labels use fixed outcome values without account or order IDs. There is no external collector or alert delivery in this stage. The default Micrometer registry is suitable for local checks and can be scraped by an authorized local collector.

Stage 2D-B should add durable reconciliation run history and alert delivery, load-test long per-account histories and matcher scan latency, and add deployment controls for exclusive matcher ownership. Each account still needs a full execution replay. Validate a PostgreSQL backup and rehearse any H2 import before cutover.
