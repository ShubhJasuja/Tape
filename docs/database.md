# Tape database foundation

PostgreSQL serves authenticated accounts and opt-in synthetic Demo trading. The loopback H2 terminal retains its existing trading and research. No legacy data was moved. See the [trading architecture](trading.md).

## Profiles and local setup

| Profile | Application JDBC data source | Flyway |
| --- | --- | --- |
| `h2-legacy` (default) | Existing file `./data/tape.mv.db` with `schema.sql` | Disabled |
| `postgres-dev` | Disposable in-memory H2 with `schema.sql` | PostgreSQL at `jdbc:postgresql://127.0.0.1:5433/tape` by default |
| `accounts` | PostgreSQL through the limited runtime role | Disabled by default. Controlled migration runs use a separate identity. |

Choose one database mode. Add `accounts-local` only to `accounts` for loopback HTTP development. See the [authentication guide](authentication.md). The in-memory database in `postgres-dev` is deliberate. Starting that profile leaves the file-backed ledger alone, and orders placed through it disappear on application restart. The postgres-dev profile applies migrations without directing orders to PostgreSQL. PostgreSQL support does not enable any additional market data.

1. Start Docker and set `TAPE_POSTGRES_PASSWORD` to a private development password in the current environment. Keep it out of source control and command history.
2. Run `docker compose up -d --wait postgres`. The service publishes PostgreSQL only on loopback and stores its database in the `tape-postgres` named volume.
3. Run `./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=postgres-dev" "-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC"` in PowerShell. Use `./mvnw` on Linux or macOS. The JVM timezone flag makes the JDBC startup timezone portable across PostgreSQL images. Tape still calculates trading hours in Asia/Kolkata. Flyway validates and applies the PostgreSQL migrations on startup. The existing frontend build is still required to use the terminal.
4. If the port conflicts, set `TAPE_POSTGRES_PORT` for Compose and set `TAPE_POSTGRES_URL` to the matching JDBC URL for the application. `TAPE_POSTGRES_USER` defaults to `tape`.
5. Use `docker compose stop postgres` to stop the database and retain its volume. Do not remove the volume to resolve a migration problem.

Run `./mvnw.cmd --batch-mode clean verify` for the original suite. With Docker running, use `./mvnw.cmd --batch-mode clean verify "-Dtape.postgres.tests=true"` to include the PostgreSQL suite. It provisions an isolated PostgreSQL container, runs the real profile against it, restarts the Spring context, and checks key constraints and append-only protection. It uses a separate in-memory H2 database and disables Upstox and Ollama. The CI workflow opts into this suite.

## Schema and invariants

```text
users ──< trading_accounts ──1 wallets ──< cash_ledger_entries
  │             ├──< orders ──< executions ──< cash_ledger_entries
  │             └──< positions >── instruments
  └──< watchlists ──< watchlist_items >── instruments
```

- `users` stores a unique identity issuer and subject. It contains no authentication credentials.
- `trading_accounts` allows one INR account per user per mode. Modes are `DEMO` and `PERMITTED_MARKET_DATA`. The latter records a data mode and grants no entitlement. `wallets` and `positions` enforce nonnegative balances, holdings and reservations. A reservation cannot exceed its corresponding total.
- `orders` ties an instrument to an account and has a unique account-scoped idempotency key. `executions` binds the same account and instrument as its order via a composite foreign key. A database-assigned sequence provides stable sorting independent of clock timestamps. Sequence allocation does not guarantee transaction commit order.
- `cash_ledger_entries` permits one opening credit per account and at most one posting per execution and posting kind. A composite foreign key prevents a posting from referencing another account's execution. Execution and ledger rows reject updates, deletes, and truncation via triggers. Schema owners and database administrators can alter these protections. Future runtime roles must lack DDL and trigger administration privileges.
- INR amounts use bounded `bigint` paise. PostgreSQL rejects values outside the integer type, and check constraints enforce tighter business bounds. The execution gross check multiplies in `numeric` before comparison to avoid overflow in the check itself. Future application arithmetic must use checked operations and test those bounds before writes.
- `watchlists` belong to users, with unique names per owner and unique instruments per list. Instruments are unique per NSE/BSE listing.

`V1__account_and_trading_schema.sql` creates the ten tables, relationships, checks and indexes. `V2__protect_append_only_records.sql` installs mutation guards. `V3__local_identity_and_account_availability.sql` adds unique local credentials and account trading availability. V4 adds the synthetic instruments, account row lock, order rejection code, and single synthetic fill constraint. Flyway tracks applied versions and validates checksums. It does not auto-baseline an unmanaged database, and its clean operation is disabled.

The schema does not enforce authorization, order transitions, the sum of executions against order quantity, or reconciliation between postings and wallet/position projections. Account reads are authorized by the Stage 2B application. The Stage 2C engine enforces order transitions and accounting through transactions and verifies projections against durable records. No concurrency guarantee follows from this schema alone.

## Backup, migration and rollback

For H2 backup and restore, follow the [README procedure](../README.md#back-up-the-local-h2-database). Hold a writer-excluding read handle after a graceful shutdown, copy to a private timestamped folder, compare SHA-256 checksums, and query a disposable restore with the same H2 version. Keep both the legacy file and verified backup before rehearsing an import.

There is no legacy data migration through Stage 2C. To return to the legacy app, choose `h2-legacy`; it does not use the PostgreSQL schema. For PostgreSQL changes, take a verified backup before applying new migrations, prefer a forward corrective migration, and restore into a new database if recovery is needed. Never edit an applied migration or use Flyway clean as rollback.

In Stage 2D, add reconciliation monitoring and rehearse any legacy data migration before switching existing users. Rehearse a legacy import from a backup into an explicitly designated owner's accounts and reconcile cash, positions, cost basis, fees, orders and reservations by mode. Freeze legacy writes at cutover. Once PostgreSQL accepts writes, rollback requires reconciling them before resuming H2. See [ADR 0001](adr/0001-account-storage-and-ledger.md).
