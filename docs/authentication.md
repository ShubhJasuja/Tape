# Tape account workspace

The account workspace provides authenticated PostgreSQL balances and optional synthetic Demo trading. Registration provisions a demo wallet and a live-data wallet with ₹10,00,000 each. Demo trading is off by default and can be enabled with the [trading setup](trading.md#enable-a-local-demo-workspace). Live-data trading stays disabled. The existing H2 terminal remains a separate loopback development mode with shared state.

## API contract

Send requests to the same origin. There is no bearer token. Before each POST below, fetch a CSRF token and send its value in the returned header. After login or logout, fetch a fresh token for the next POST. Keep the token in memory.

| Method and path | Request | Response |
| --- | --- | --- |
| GET `/api/v1/auth/csrf` | No login required | 200 with `{headerName, token}`. Sets a session cookie when needed. |
| POST `/api/v1/auth/register` | JSON with `email` and `password` | Generic 202 in the HTTPS profile. Loopback development returns 201, or 409 for an existing normalized email. Invalid fields return 400 and oversized bodies return 413. |
| POST `/api/v1/auth/login` | URL-encoded form fields `email` and `password` | 204 and a rotated session ID on success. 401 with the same response for unknown email and incorrect password. |
| POST `/api/v1/auth/logout` | No body | 204. Invalidates the session and clears its cookie. Idempotent with a valid CSRF token. |
| GET `/api/v1/auth/me` | Session cookie | 200 with `{email}`, or 401. |
| GET `/api/v1/accounts` | Session cookie | 200 with an array of accounts owned by the principal, or 401. |
| GET `/api/v1/accounts/{accountId}` | Session cookie and account UUID | 200 for an owned account, 404 for a missing or foreign account, 401 without a session. |

Each account contains `id`, `mode` (`DEMO` or `LIVE_DATA`), `currency` (`INR`), `balancePaise`, `reservedPaise`, and `tradingEnabled`. Browser-supplied owner identifiers have no effect. Missing CSRF tokens return 403. Authentication throttling returns 429 with `Retry-After`.

Emails are case-insensitive and normalized to lowercase. The local form accepts ASCII email addresses up to 254 characters. Passwords must have at least 12 characters and fit within bcrypt's 72-byte UTF-8 limit. PostgreSQL stores only bcrypt hashes. Registration and initial funding commit together. A repeated normalized email creates no new wallets or ledger postings. In the HTTPS profile registration returns generic 202 and email verification is required. See [identity and operations readiness](identity-readiness.md) for verification, recovery, session revocation and delivery setup.

## Profiles and local setup

1. Start the [loopback PostgreSQL Compose service](database.md#profiles-and-local-setup) and apply Flyway migrations through V12 with the database owner using the `postgres-dev` schema profile. Stop that application process afterward. Keep the original H2 profile separate.
2. As a database administrator, create a LOGIN role named `tape_runtime` with no role memberships, schema ownership, DDL, or administrative privileges. Set its password using your secret manager or the interactive psql password command. Run [accounts-runtime-grants.sql](accounts-runtime-grants.sql) against the `tape` database as its owner. If you use a differently named database or role, adjust the grant script for that environment.
3. Set `TAPE_ACCOUNTS_URL` if the PostgreSQL JDBC URL differs from the Compose default. Set `TAPE_ACCOUNTS_USER` if the runtime role differs, and provide `TAPE_ACCOUNTS_PASSWORD` via the environment. Build the frontend as described in the README.
4. Start local account mode with:

   ```powershell
   ./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=accounts,accounts-local" "-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC"
   ```

   Open [Tape](http://127.0.0.1:8080). `accounts-local` allows HTTP cookies and forces a loopback listener. Use it only for local development.

The `accounts` profile alone requires HTTPS and Secure cookies. Keep the backend private and terminate TLS directly or use a trusted same-origin reverse proxy. With a proxy, use `TAPE_FORWARD_HEADERS_STRATEGY=native` and set `TAPE_TRUSTED_PROXY_REGEX` to the real internal proxy addresses. Strip client-supplied forwarding headers at the edge and set the scheme from the actual TLS connection. By default the app ignores forwarding headers and rejects plain HTTP in this profile. Never proxy the legacy or `accounts-local` mode.

Flyway is disabled by default for the runtime process. A controlled migration run can set `TAPE_ACCOUNTS_MIGRATE=true` with separate `TAPE_MIGRATION_USER` and `TAPE_MIGRATION_PASSWORD`; use a schema-owner identity for migration only, then remove those credentials from the app environment.

## Security assumptions and limits

- Spring Security authenticates a server-side session, rotates its ID on login, and uses an HttpOnly, SameSite=Lax cookie with a 20-minute inactivity timeout. The account UI stores no credentials, session identifiers, or CSRF token in browser storage.
- The security chain explicitly allows the account and [synthetic trading APIs](trading.md#trading-api) and static entry assets. Other routes are denied. The legacy controller and scheduler are absent from account mode. No credentialed cross-origin policy is configured.
- The HTTPS accounts profile stores sessions in PostgreSQL using Spring Session JDBC. Replicas with the same database and compatible application versions share sign-in, rotation and logout. The embedded container still owns loopback development sessions. Apply V12 and reapply the runtime grants before rolling out the HTTPS profile. Sessions held in the previous in-memory store expire at the cutover. Treat session tables as sensitive and be prepared to expire sessions during incompatible upgrades.
- HTTPS authentication admission uses an in-process IP prefilter and atomic, database-time minute buckets shared across replicas. It caps CSRF at 90 per address and 900 globally, login at 20 per address, 8 per normalized email and 600 globally, and registration at 10 per address and 60 globally. Buckets store SHA-256 digests and age out opportunistically. Treat digests as pseudonymous data. A database error fails authentication admission closed. Apply edge limits before allowing public traffic.
- Account authorization is enforced in server queries. The database runtime role starts with provisioning and account-read privileges. Synthetic trading uses additional narrowly scoped grants. It can still read across owners at the SQL level, so account endpoints must preserve principal scoping. The HTTPS flow now requires verified email and supports password recovery and session revocation. Loopback development retains its existing registration response contract.
- Legacy H2 accounts remain shared. No H2 users, trades, or watchlists were imported. Never serve the legacy routes through a public proxy.

Run the HTTP and PostgreSQL security integration tests with `./mvnw.cmd --batch-mode clean verify "-Dtape.postgres.tests=true"` and Docker running. The tests use a disposable database and separate limited runtime role.
