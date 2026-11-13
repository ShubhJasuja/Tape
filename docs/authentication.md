# Tape account workspace

The account workspace provides authenticated PostgreSQL balances and optional synthetic Demo trading. Registration provisions a demo wallet and a live-data wallet with ₹10,00,000 each. Demo trading is off by default and can be enabled with the [trading setup](trading.md#enable-a-local-demo-workspace). Live-data trading stays disabled. The existing H2 terminal remains a separate loopback development mode with shared state.

## API contract

Send requests to the same origin. There is no bearer token. Before each POST below, fetch a CSRF token and send its value in the returned header. After login or logout, fetch a fresh token for the next POST. Keep the token in memory.

| Method and path | Request | Response |
| --- | --- | --- |
| GET `/api/v1/auth/csrf` | No login required | 200 with `{headerName, token}`. Sets a session cookie when needed. |
| POST `/api/v1/auth/register` | JSON with `email` and `password` | 201 with no body. 400 for invalid fields, 409 for an existing normalized email, 413 for an oversized or unbounded body. Registration does not log in. |
| POST `/api/v1/auth/login` | URL-encoded form fields `email` and `password` | 204 and a rotated session ID on success. 401 with the same response for unknown email and incorrect password. |
| POST `/api/v1/auth/logout` | No body | 204. Invalidates the session and clears its cookie. Idempotent with a valid CSRF token. |
| GET `/api/v1/auth/me` | Session cookie | 200 with `{email}`, or 401. |
| GET `/api/v1/accounts` | Session cookie | 200 with an array of accounts owned by the principal, or 401. |
| GET `/api/v1/accounts/{accountId}` | Session cookie and account UUID | 200 for an owned account, 404 for a missing or foreign account, 401 without a session. |

Each account contains `id`, `mode` (`DEMO` or `LIVE_DATA`), `currency` (`INR`), `balancePaise`, `reservedPaise`, and `tradingEnabled`. Browser-supplied owner identifiers have no effect. Missing CSRF tokens return 403. Authentication throttling returns 429 with `Retry-After`.

Emails are case-insensitive and normalized to lowercase. The local form accepts ASCII email addresses up to 254 characters. Passwords must have at least 12 characters and fit within bcrypt's 72-byte UTF-8 limit. PostgreSQL stores only bcrypt hashes. Registration and initial funding commit together. A repeated normalized email rolls back the attempted user and creates no new wallets or ledger postings.

## Profiles and local setup

1. Start the [loopback PostgreSQL Compose service](database.md#profiles-and-local-setup) and apply Flyway migrations through the `postgres-dev` schema profile with the database owner. Wait for V4 to complete, then stop that application process. Keep the original H2 profile separate.
2. As a database administrator, create a LOGIN role named `tape_runtime` with no role memberships, schema ownership, DDL, or administrative privileges. Set its password using your secret manager or the interactive psql password command. Run [accounts-runtime-grants.sql](accounts-runtime-grants.sql) against the `tape` database as its owner. If you use a differently named database or role, adjust the grant script for that environment.
3. Set `TAPE_ACCOUNTS_URL` if the PostgreSQL JDBC URL differs from the Compose default. Set `TAPE_ACCOUNTS_USER` if the runtime role differs, and provide `TAPE_ACCOUNTS_PASSWORD` via the environment. Build the frontend as described in the README.
4. Start local account mode with:

   ```powershell
   ./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=accounts,accounts-local" "-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC"
   ```

   Open [Tape](http://127.0.0.1:8080). `accounts-local` allows HTTP cookies and forces a loopback listener. Use it only for local development.

The `accounts` profile alone requires HTTPS and Secure cookies. For a future deployment, keep the backend private and terminate TLS directly or use a trusted same-origin reverse proxy. With a proxy, explicitly configure Tomcat native forwarded headers and its internal proxy addresses, strip client-supplied forwarding headers at the edge, and set the scheme from the actual TLS connection. The default does not trust forwarded headers. The backend rejects plain HTTP; configure the public TLS edge to redirect HTTP if needed. Never proxy the legacy or `accounts-local` mode.

Flyway is disabled by default for the runtime process. A controlled migration run can set `TAPE_ACCOUNTS_MIGRATE=true` with separate `TAPE_MIGRATION_USER` and `TAPE_MIGRATION_PASSWORD`; use a schema-owner identity for migration only, then remove those credentials from the app environment.

## Security assumptions and limits

- Spring Security authenticates a server-side session, rotates its ID on login, and uses an HttpOnly, SameSite=Lax cookie with a 20-minute inactivity timeout. The account UI stores no credentials, session identifiers, or CSRF token in browser storage.
- The security chain explicitly allows the account and [synthetic trading APIs](trading.md#trading-api) and static entry assets. Other routes are denied. The legacy controller and scheduler are absent from account mode. No credentialed cross-origin policy is configured.
- Authentication limits are per process. They cap login attempts per source and normalized identity and registration and CSRF-token requests per source. A future shared deployment needs a trusted edge limit and shared abuse controls, particularly if proxying or running replicas. Sessions are local to the application process. Restarting it signs users out.
- Account authorization is enforced in server queries. The database runtime role starts with provisioning and account-read privileges. Synthetic trading uses additional narrowly scoped grants. It can still read across owners at the SQL level, so future account endpoints must preserve principal scoping. Email verification, password recovery, and permissions for live data remain outside this stage. A duplicate-email response indicates that the email is already registered.
- Legacy H2 accounts remain shared. No H2 users, trades, or watchlists were imported. Never serve the legacy routes through a public proxy.

Run the HTTP and PostgreSQL security integration tests with `./mvnw.cmd --batch-mode clean verify "-Dtape.postgres.tests=true"` and Docker running. The tests use a disposable database and separate limited runtime role.
