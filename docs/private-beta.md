# Tape private beta operations

Stage 2M keeps the existing modular monolith, PostgreSQL 17, JDBC sessions, account locks and coordinated workers. No financial algorithms or migrations are replaced. Market-data permissions and real-price execution remain disabled. No deployment is performed by this document.

## Access and identities

`TAPE_REGISTRATION_MODE` defaults to `CLOSED`. Set `ALLOWLIST` and a comma-separated `TAPE_REGISTRATION_ALLOWLIST` of invited email addresses before starting every node. Addresses are normalized using the same rules as login. Changes require restarting all nodes. `PUBLIC` is an explicit future opt-in, not a beta default. The generic HTTP 202 response does not reveal membership. Uninvited requests provision no user, wallet or email. Existing identities can still request verification and recover passwords while new registration is closed. Invitations are registration permission, not identity proof: the single-use emailed link and a newly chosen password are required before login. Removing an invitation does not suspend an existing user. Revoke that user's sessions and disable access through a separately reviewed administrative process if required.

Each registered identity retains independent DEMO and disabled PERMITTED_MARKET_DATA wallets, with the existing opening virtual funds. Equity and derivatives continue to use their existing separate accounting books. Practice prices and configurable practice margins are not exchange quotes or official margins. Do not populate accounts from a provider feed.

`accounts-local` is only a loopback developer shortcut and bypasses verified-email login. It cannot be combined with `beta`. Hosted beta uses `accounts,beta,brevo`, Secure/HttpOnly/SameSite=Lax session cookies, CSRF-protected writes and shared PostgreSQL authentication budgets. All instances must share registration configuration, canonical HTTPS origin and the persistent identity encryption key. No database owner password is passed to the application container.

## SMTP provider: Brevo

The optional `brevo` profile configures `smtp-relay.brevo.com:587`; the existing JavaMail sender requires STARTTLS, checks server identity, authenticates and has five-second network timeouts. No new provider SDK is needed. Configure credentials outside the repository and never paste them into an issue, chat, shell command history or committed env file:

| Variable | Source |
|---|---|
| TAPE_SMTP_USER | SMTP login shown in Brevo's SMTP settings |
| TAPE_SMTP_PASSWORD | Brevo SMTP key, **not** an API key |
| TAPE_MAIL_FROM | Verified sender on your authenticated domain |
| TAPE_PUBLIC_ORIGIN | Canonical HTTPS origin with port if nonstandard; no path/query |
| TAPE_IDENTITY_MAIL_KEY | Independently generated 32 random bytes, Base64; preserve securely across restarts and restores |
| TAPE_IDENTITY_MAIL_ENABLED | true |

Create a free account yourself, complete its transactional-sending approval and verify the sender. No paid entitlement is assumed. The [current Free plan](https://help.brevo.com/hc/en-us/articles/208589409-About-Brevo-s-pricing-plans) includes transactional email and 300 sends/day at $0. Failed quota/authentication attempts use the existing durable five-minute retry schedule, at most eight attempts, and require operator attention after exhaustion. Application readiness reports configuration and database access, not successful delivery or remaining quota. Monitor `tape.identity.mail` outcomes and queue depth/oldest age from a restricted owner connection. An unreadable encrypted item is quarantined instead of blocking other accounts. Preserve the key; request fresh links after repairing configuration. SMTP acceptance can be followed by a bounce, spam placement or duplicate delivery after a crash. Do not promise delivery based on the local fixture.

Follow [Brevo's SMTP instructions](https://help.brevo.com/hc/en-us/articles/7924908994450-Send-transactional-emails-using-Brevo-SMTP) and [domain authentication instructions](https://help.brevo.com/hc/en-us/articles/12163873383186-Authenticate-your-domain-with-Brevo-Brevo-code-DKIM-DMARC). Add the account-specific Brevo ownership record, DKIM records and one valid DMARC policy at the domain's DNS provider. Preserve existing mail-provider records. On shared IPs Brevo does not require adding SPF/MX for its domain authentication; dedicated-IP requirements differ. Never invent an SPF include, duplicate an existing SPF/DMARC record or replace another sender's policy blindly. Verify DKIM and DMARC results in received message headers; SPF results depend on the actual envelope sender. DNS control, sender verification, account approval, external TLS connectivity and mailbox delivery are operator gates, not established by API access.

Before admitting testers, send verification and recovery emails to a controlled real mailbox, inspect authentication headers, consume each link once, prove replay/expiry rejection and reset-session revocation, and verify a retry after a temporary SMTP failure. External delivery and DNS remain unverified when credentials/domain are absent. The automated test uses real JavaMail STARTTLS to an isolated local SMTP fixture, never an external provider.

## Database upgrade and recovery

Keep every application instance stopped gracefully during this procedure. `DatabaseMaintenance` starts no server, provider adapter, email sender or worker. It rejects other client connections. Disable external auto-restart/orchestration first so a stopped node cannot reconnect between gates. Never run maintenance while permitting incoming traffic. The connection check is a guard, not a distributed maintenance barrier.

Build the exact release and run from the repository root in PowerShell:

```powershell
./mvnw.cmd --batch-mode verify -Dtape.postgres.tests=true
./scripts/Upgrade-LocalPostgres.ps1 -BackupDirectory C:/Users/Admin/TapeBackups
```

The default is rehearsal only. It creates a new timestamped custom-format `pg_dump` outside the repository, compares the container and host SHA-256 checksums, restores to a no-network PostgreSQL 17 container, checks heap/index integrity and saves table counts. A second disposable restore is exposed only on a random loopback port for the packaged Java maintenance tool. Its V4 original financial columns and instrument identities are compared against SHA-256 fingerprints before and after V5–V12. Added identity columns and new catalog rows do not invalidate original records. Existing V5 intentionally changes tick-size metadata and includes legacy fictional discovery rows; these are existing migration content, not new listings added by Stage 2M. Historical order IDs and all original financial fields must match exactly.

The rehearsal reapplies the existing account/equity/derivative grants, rejects elevated runtime roles, verifies immutable-record mutation privileges are absent, and reconciles all wallets to cash journals, DEMO equity execution/holdings/reservations, and any existing derivative books. It runs reconciliation again using `SET ROLE tape_runtime`, without changing or exposing the runtime password. No derivative book is created merely to reconcile it. A mismatch stops the procedure before the source migration. Empty positions/orders in the actual source cannot establish a populated production upgrade; the automated schema and trading tests cover nonempty references separately.

After reviewing successful rehearsal results, the previously authorized local upgrade can be executed with:

```powershell
./scripts/Upgrade-LocalPostgres.ps1 -BackupDirectory C:/Users/Admin/TapeBackups -Apply
```

This repeats the fresh backup and every gate, compares the source again immediately before Flyway, migrates only exact V4 to V12, reapplies runtime grants, reconciles under owner and runtime roles, and runs integrity checks. It never drops/restores over the source or resets its runtime password. Backup, financial fingerprint and restore/upgrade JSON reports stay together outside Git. Fingerprints contain counts/columns/hashes, not raw rows, passwords or email addresses; protect them as private operational artifacts anyway. The source and backup are logical snapshots, not byte-identical database files; the checksums compare the dump before/after copying. Existing H2 files and their backups are untouched.

For recovery, keep Tape stopped, preserve a post-failure dump for diagnosis, restore the verified V4 backup into a **new** database/container with no production clients, run integrity/count/fingerprint checks, use the V4-compatible application and grants, then change the connection configuration for a controlled cutover. Do not restore into the original database or run invented reverse migrations. PostgreSQL/Flyway transactions protect each migration, but eight migrations are not one atomic rollback unit. On partial failure, restore to a new database or correct forward only after review. Securely retain database-owner/runtime credentials, SMTP configuration and encryption key separately; `pg_dump --no-acl` does not back up login passwords or host DNS/TLS configuration. Encrypt off-host backups, restrict ACLs and test retention/restoration before real tester data is stored.

## Deployment preparation

The supplied `deploy/compose.beta.yaml` builds React and Java 21 in a multi-stage image. Caddy serves the same HTTPS origin and forwards to the private Spring Boot port. PostgreSQL has no published port. The default proxy binds **loopback only**, HTTPS 8443 and HTTP 8088. For localhost Caddy uses its local CA; install/trust it only on a deliberate local test machine. A domain requires DNS/TLS ownership and a separately approved ingress/firewall/port mapping change. Public ACME normally requires ports 80/443. These artifacts do not publish an application or purchase infrastructure.

Use a private env file outside Git, readable only by the operator, with the SMTP/identity/access variables above plus separate `TAPE_POSTGRES_PASSWORD`, `TAPE_ACCOUNTS_PASSWORD`, a random 32–128 URL-safe-character `TAPE_OPS_TOKEN`, and `TAPE_BETA_HOST`. Use `TAPE_PUBLIC_ORIGIN=https://localhost:8443` for the default local mapping. Never run `docker compose config` without `--quiet` in shared logs because interpolation can expose secrets.

```powershell
$betaEnv = 'C:/private/tape-beta.env'
docker compose --env-file $betaEnv -f deploy/compose.beta.yaml config --quiet
docker compose --env-file $betaEnv -f deploy/compose.beta.yaml build app
docker compose --env-file $betaEnv -f deploy/compose.beta.yaml up -d postgres
docker compose --env-file $betaEnv -f deploy/compose.beta.yaml --profile maintenance run --rm migrate
docker compose --env-file $betaEnv -f deploy/compose.beta.yaml up -d app proxy
```

`init-runtime.sh` only creates an unprivileged runtime role on a new empty volume. The maintenance command initializes an empty database or validates an existing V12 and applies grants. It refuses existing older schemas, which require the backup-gated procedure. Do not point this fresh-beta compose project at the existing local volume. Never use `down -v`, `volume rm` or destructive schema initialization. Runtime migrations remain disabled.

The application trusts forwarding headers only from Caddy's fixed private IP `172.30.86.10`. Caddy replaces forwarded client/protocol headers, removes `Forwarded`, and blocks operations URLs. Do not expose the app directly or expand trust to arbitrary clients. Existing JDBC-session tests and the HTTPS fixture verify cross-node sessions/revocation, forged forwarding rejection and restart persistence. Real domain certificates and the selected host's ingress are not validated by a localhost fixture. The fixed-IP compose is deliberately one application instance. Do not use `--scale app`; use distinct instance addresses, consistent secrets/config and budget database connections when introducing another host. The provider feed remains single-owner and unavailable to beta users.

Start with one 4 GiB VM: Caddy (~128 MiB limit), Java (1 GiB limit), PostgreSQL (768 MiB limit), plus host/cache headroom. A 2 GiB host is a tight experiment, not the recommended budget with these container limits. Each app pool is at most 8 connections; PostgreSQL max is 50. Budget up to four extra coordination connections per instance (one for each concurrently active worker), session cleanup and operator headroom; measure before adding instances. Tomcat has 60 threads/256 connections with bounded accepts and 5-second pool acquisition. No Redis, Kafka, Kubernetes or managed database is required for this beta.

Daily dumps plus encrypted off-host retention are required. Schedule `Backup-Postgres.ps1` (container/database parameters supported) with Task Scheduler or an equivalent host operator task, alert on nonzero exit, and run `Test-PostgresBackup.ps1` regularly on a separate machine. A backup on the same VM is not disaster recovery. Define a 24-hour RPO for daily dumps and measure restore time for an RTO; no unmeasured SLA is promised. Restrict SSH/host access and Docker socket access. Graceful `docker compose stop app` gets 40 seconds, allowing the application's 30-second shutdown. Rolling restarts preserve JDBC sessions and worker advisory locks, but an already authorized in-flight transaction may finish before revocation.

Readiness/liveness/metrics and reconciliation are loopback + bearer-token protected and blocked by Caddy. The image healthcheck reads readiness from container loopback. Collect structured stdout with host log rotation, never request bodies/query tokens/SMTP debug logging. Alert on readiness failure, Hikari exhaustion, reconciliation issues, queue retries/unreadable mail, scheduler lag, disk space and failed backup checks. A green readiness result is not a deliverability guarantee.

## Validation

```powershell
npm --prefix frontend ci
npm --prefix frontend run build
./mvnw.cmd --batch-mode verify -Dtape.postgres.tests=true -Dtape.browser.tests=true
```

Java 21, Node 22 and installed Chrome (`CHROME_PATH` optional) are required. Tests use PostgreSQL 17 Testcontainers. The browser test creates local disposable TLS keys, STARTTLS mail capture, two non-local accounts-profile servers and a loopback HTTPS reverse proxy. No fixture endpoints exist in production code. Chrome's certificate bypass is confined to its fresh test profile, and never used by the application SMTP sender. It exercises actual API/UI verification, equity/options/futures trades, P&L/journal navigation, sessions across nodes/restart, reset and revocation. Six additional verified users perform 180 authenticated portfolio reads at concurrency 18; results and three screenshots are ignored artifacts under `target/beta-browser`. This is a repeatable smoke/load baseline, not a public capacity claim or soak test. Existing concurrent financial/order/worker tests remain the correctness foundation.

## Cost and launch gates

As checked 2026-11-18: Brevo Free $0/month within its quota. [DigitalOcean regular VMs](https://www.digitalocean.com/pricing/droplets) list 2 GiB/1 vCPU at $12/month and 4 GiB/2 vCPU at $24/month. The recommended latter is $288/year before tax, domain, encrypted off-host backup storage and optional [weekly VM backups](https://docs.digitalocean.com/products/backups/details/pricing/) at 20% of VM cost ($4.80/month on $24). Estimated host+weekly snapshot total $28.80/month/$345.60/year plus those extras. Snapshots do not replace verified logical off-host dumps. Existing computer + localhost validation costs $0 in purchased services. No resources or subscriptions were created.

An invited remote beta still requires SMTP credentials/account approval, sender/domain/DNS verification, a controlled real-mail journey, an approved host/ingress with valid TLS, protected persistent secrets, actual off-host backup retention/restore rehearsal and host monitoring. Public launch additionally needs privacy/retention/deletion and abuse/support policies, capacity/soak and security review, and explicit provider/exchange rights before real data display, redistribution or market-price simulation. Neither practice trading nor a broker token establishes those rights.
