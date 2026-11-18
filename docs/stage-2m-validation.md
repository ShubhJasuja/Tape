# Stage 2M verification record

Date: 2026-11-18. No public deployment, external SMTP delivery, real provider request or real-price virtual fill was performed.

## Stage 2L release review

PR [#6](https://github.com/ShubhJasuja/Tape/pull/6) was reviewed across registration/verification/recovery, shared authentication budgets, CSRF, session versions/JDBC revocation, encrypted queue, migrations and restore scripts. Confirmed defects fixed in `50f519f` before merge: an unreadable encrypted queue item could repeatedly roll back delivery and block other users; registration and resend used separate per-email send budgets; the confirmation form lacked the backend's UTF-8 byte-limit explanation. The queue now quarantines unreadable work, shares the verification budget and validates password bytes. Regression: 109 backend tests, zero failures/errors/skips; frontend build and both GitHub checks passed. History-preserving merge `67eb2854e9603203a0420809fde00c8107edf837`; [main CI passed](https://github.com/ShubhJasuja/Tape/actions/runs/44146833188).

## Database execution

Original: local `tape-postgres-1`, PostgreSQL 17.11, loopback port 5433, schema V4. No other source client connections existed. No application was stopped or force-killed in Stage 2M.

Fresh verified backup (outside Git):

```text
C:/Users/Admin/TapeBackups/tape-20261118-000434-bfcb5a3b94d3465793e23a2ad9498e85.dump
SHA-256: CFDF457761B53697F945D07701FB69C69D239D26CFE1123B416674C2BB05E387
```

Container dump and copied archive checksums matched. A no-network PostgreSQL 17 restore passed heap/index checks and record counts. A separate restored copy passed the existing V5–V12 migrations, original-field SHA-256 comparisons, equity/wallet/derivative reconciliation and runtime-role access. Only then did the gated script upgrade the source to V12, apply existing grants, repeat those checks and run source heap/index verification. The original database was not reset, restored over or deleted; its existing runtime password was not changed. H2 was untouched.

| Preserved source data | Before | After |
|---|---:|---:|
| Users | 1 | 1 |
| Local credentials | 1 | 1 |
| Trading accounts | 2 | 2 |
| Wallets | 2 | 2 |
| Cash ledger entries | 2 | 2 |
| Wallet balance total (paise) | 200,000,000 | 200,000,000 |
| Cash ledger total (paise) | 200,000,000 | 200,000,000 |
| Orders / executions / positions | 0 / 0 / 0 | 0 / 0 / 0 |
| Watchlists / watchlist items | 0 / 0 | 0 / 0 |

All original instrument identity fields and financial columns matched their pre-upgrade fingerprints. Existing V5 adds legacy fixture metadata and changes tick metadata as documented in that migration. No new migration or new listing source was introduced by Stage 2M. Existing users require email verification for non-local login; their original credential hashes and balances remain preserved. No derivative book existed in the source, so derivative reconciliation there was vacuous; populated-book correctness remains covered by regression and browser tests.

The initial script attempt stopped before snapshot/migration because PowerShell split an unquoted JVM `-D` argument. It was corrected before a new backup/rehearsal/upgrade succeeded. A new unit test initially exposed the host's `Asia/Calcutta` timezone alias incompatibility with PostgreSQL and was corrected to UTC. A separate optional package attempt encountered a local Maven cache lock; the full verify/package and Docker build subsequently succeeded. The first Docker build exposed Alpine's existing `tape` group; the final image uses a dedicated UID/GID 10001 `tapebeta` identity. No failed attempt modified source financial records.

## Automated and deployment results

| Check | Actual result |
|---|---|
| Full backend verify with PostgreSQL and browser enabled | 114 tests, zero failures/errors/skips |
| React production build | passed |
| V4 upgrade/fingerprint/grant drift test | passed; deliberate wallet drift and column-level immutable-record UPDATE privilege rejected |
| Local STARTTLS delivery | passed using the real JavaMail SMTP implementation and isolated fixture |
| Chrome full user journey | passed |
| Two-node sessions and first-node restart | passed |
| Password recovery invalidates old sessions on both nodes | passed |
| Equity, options and futures trades/journals | passed; final fixture financial reconciliation passed |
| Responsive views | 1440/768/390 px, no page overflow |
| Local multi-stage Docker image build | passed |
| Actual Caddy/PostgreSQL/Tape Compose deployment | HTTPS, redirect, Secure/HttpOnly cookies, readiness and blocked operations paths passed |
| Closed registration in deployment | HTTP 202, zero credentials and zero queued mail |
| External email/DNS/provider connectivity | not attempted / not verified |

Full-suite command:

```powershell
./mvnw.cmd -o --batch-mode verify -Dtape.postgres.tests=true -Dtape.browser.tests=true -Dtape.build.directory=target/stage-2m-final "-DargLine=-Xmx512m -XX:+UseSerialGC"
npm --prefix frontend run build
docker build -f deploy/Dockerfile -t tape-beta:local .
./scripts/Test-BetaDeployment.ps1
```

The full local verify took 11m28s while Docker validation also used the host. The final grant-check hardening was separately rechecked with `DatabaseMaintenanceTest`; GitHub CI runs the complete final branch. Browser artifacts/logs and disposable TLS material remain ignored under `target`; the fixture deployment's uniquely owned containers, volumes and network were cleaned up. Only the existing persistent PostgreSQL container was left running.

Load during the full regression: 6 additional verified users, 18 concurrent clients, 180 authenticated portfolio requests, 0 failures, 3.11 seconds, 57.89 requests/second, p50 276.1 ms, p95 459.7 ms, p99 530.5 ms. An earlier isolated browser run achieved 92.55 requests/second with p95 349.6 ms. Neither establishes hosted capacity; these are short loopback read-load measurements. Concurrent order/margin/cancellation correctness is tested separately by the existing PostgreSQL suites. Long-running saturation, host failure and network-partition drills remain launch work.

The browser journey identified an invalid flat futures reduce-only ticket: the frontend now blocks that submission, explains how to correct it and suppresses misleading margin estimates. Server-side execution policy and all financial algorithms are unchanged.

## Remaining gates

Brevo credentials, account approval and domain/DNS control are unavailable. External verification/recovery delivery, received-message SPF/DKIM/DMARC results, bounce handling and real quota behavior remain unverified. Configure them using the [beta runbook](private-beta.md), then complete a controlled real-mail journey before inviting remote testers.

An approved host and ingress, real domain certificate validation, persistent protected secrets, encrypted off-host backups/restore drills, disk/queue/worker alerting and host-specific capacity measurements are still required. These changes prepare a private beta and do not claim public-launch readiness. Provider/exchange permissions, privacy/retention/abuse policies and a public security/capacity review remain separate public-launch gates. Stage 2M must be reviewed and merged separately; it is not automatically merged or deployed.
