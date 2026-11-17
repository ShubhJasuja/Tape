# Identity and operations readiness

## Registration and recovery

The HTTPS accounts profile accepts registration with a generic 202 response for both new and existing valid email addresses. It provisions a pending identity and independent wallets in one transaction. Authentication requires verified email. A verification link asks the mailbox owner to choose a password, so a password supplied by an earlier registration attempt does not grant that requester access. Existing identities are unverified after V12 and need the same verification process before signing in through the HTTPS profile.

The accounts-local loopback workflow keeps its existing registration and sign-in behavior. Its data is still subject to ownership and session-version checks. Never route this profile through a public proxy.

| Route | Behavior |
| --- | --- |
| POST /api/v1/auth/register | HTTPS profile returns 202. Loopback development returns 201 and provisions the existing local flow. |
| POST /api/v1/auth/verification | JSON email. Generic 202. Queues a verification link only for an eligible unverified identity. |
| POST /api/v1/auth/recovery | JSON email. Generic 202. Queues a reset link only for an eligible verified identity. |
| POST /api/v1/auth/verify | JSON token and password. 204 on successful verification, 400 for invalid, used, expired or wrong-purpose tokens. |
| POST /api/v1/auth/reset | JSON token and password. 204 on reset and revocation, with the same 400 failure contract. |
| POST /api/v1/auth/sessions/revoke | Authenticated and CSRF-protected. Increments the identity's session version and invalidates all prior sessions, including the caller. |

All writes require a fresh CSRF token. Recovery and verification requests share address and global request budgets, plus a persistent email budget before looking up the identity. Unknown identities have the same response. Authentication uses bcrypt for unknown identities and verifies the password before checking verified-email status. Email eligibility affects queued work only.

Tokens contain 256 random bits and are stored as SHA-256 digests. Verification tokens expire after 24 hours and reset tokens after 30 minutes. Each purpose has at most one outstanding token per identity. New issuance invalidates the previous token of that purpose. Confirmation locks the identity, checks the expiry using PostgreSQL time, deletes the token and updates the password and session version in the same transaction. Concurrent consumption succeeds once. Confirmation invalidates every outstanding identity action and prior database session.

Every authenticated API request checks the current session version. This also rejects an old session saved by a request that was in flight during password reset. Requests admitted before a reset can finish their existing transaction. The filter fails closed on database errors.

## Email setup

Apply Flyway V12 and the updated account runtime grants first. Enable delivery only after configuring all of these values through the server environment or secret manager.

| Setting | Purpose |
| --- | --- |
| TAPE_IDENTITY_MAIL_ENABLED | true enables the verification and recovery service. Default false makes these operations unavailable. |
| TAPE_PUBLIC_ORIGIN | Fixed HTTPS origin for links, without a path, query, user information or fragment. It is independent of the request Host header. |
| TAPE_MAIL_FROM | Sender email address authorized by the email operator. |
| TAPE_IDENTITY_MAIL_KEY | Base64-encoded 32-byte random AES key. Store separately from PostgreSQL and include it in controlled disaster recovery. |
| TAPE_SMTP_HOST, TAPE_SMTP_PORT | SMTP server and port, default port 587. |
| TAPE_SMTP_USER, TAPE_SMTP_PASSWORD | SMTP credentials. |
| TAPE_IDENTITY_MAIL_SCHEDULED | Default true. An operator can pause sending while leaving the encrypted queue intact. |

SMTP requires STARTTLS with certificate identity checks and five-second connection, read and write timeouts. No delivery provider is configured by this change. Verify SPF, DKIM, DMARC, delivery, bounces and abuse handling with the chosen sender before public registration.

V12 stores outbound tokens using AES-256-GCM with a random nonce and the action UUID as associated data. The mail worker uses existing PostgreSQL worker coordination, locks queued work and retries transient mail errors up to eight attempts at five-minute intervals. Delivery is at least once. A crash after SMTP acceptance can resend the same single-use link. The plaintext token is absent from HTTP responses and logs. Successful delivery deletes its encrypted queue copy. Expired actions and their queued mail are removed by the worker.

Links use URL fragments. The browser reads the token into memory and removes it from the address before rendering the form. Confirmation requires an explicit POST, so email previews cannot consume the link. Recovery pages send a no-referrer policy and use the existing same-origin assets. Keep application and edge request-body logging disabled.

## HTTPS and replicas

The native forwarded-header strategy is off by default. If the actual TLS edge terminates HTTPS, set TAPE_FORWARD_HEADERS_STRATEGY=native and restrict TAPE_TRUSTED_PROXY_REGEX to that edge's internal addresses. Strip client-supplied forwarding headers at the edge, overwrite the forwarded scheme and client address, keep direct backend access private, and permit only the expected host at the edge.

The integration suite verifies rejection of HTTP and forged forwarding headers from an untrusted peer, acceptance through the trusted loopback proxy fixture, Secure/HttpOnly/SameSite cookies, shared sign-in and logout, credential reset on another instance, and refusal of old sessions. The eventual deployed TLS certificate, proxy network and public origin require validation in that environment.

Protected readiness checks verify PostgreSQL availability, session/queue privileges and identity delivery configuration. Metrics include tape.identity.mail outcomes queued, sent and retry, tape.identity.confirmations, and existing worker, authentication and reconciliation metrics. Alert on persistent retries, exhausted attempts, an old pending queue, readiness failures, worker errors and financial reconciliation mismatches. Configuration readiness does not prove external SMTP delivery.

## Backups and restoration

Run scripts/Backup-Postgres.ps1 with a destination outside the repository. It makes a consistent online pg_dump archive, copies it out of the source container and compares SHA-256 values before writing a manifest. Run scripts/Test-PostgresBackup.ps1 with the archive path. The verifier starts a disposable PostgreSQL 17 container with no network or exposed ports, checks the manifest, restores into it, runs pg_amcheck and records every restored table's row count. The source database remains untouched. The verifier discards owner and ACL statements so local role names cannot affect isolation.

The source dump is sensitive and contains credential hashes, personal data and financial records. Restrict the backup directory, encrypt backups at rest and set a retention policy. Store migration history, deployment versions, email encryption keys, and separate role/grant definitions in their appropriate recovery stores. This logical backup omits cluster roles and provides recovery to its snapshot. Point-in-time recovery, WAL archiving, off-host copies and a scheduled restore drill remain public-launch requirements.

An unreadable encrypted mail item is quarantined at its retry limit and counted as unreadable, so subsequent users' messages can still be sent. Preserve the configured encryption key across restarts. If a key is lost, invalidate the affected actions and issue new links after correcting configuration.

Before restoring publicly, stop new writes, restore into a new database, validate checksums, schema, record counts and all equity/derivative ledger reconciliations, apply reviewed grants and compare application versions. Invalidate active sessions, expire outstanding identity actions, rotate affected credentials, configure the intended runtime environment, verify readiness and resume traffic only after recovery sign-off. A prior identity mail encryption key is needed to recover pending outbound mail, or invalidate that mail and issue fresh links.

The automated schema drill restores the current V12 schema and existing orders into an independent disposable database and checks all table counts and heap/index integrity. The local persistent V4 database was also backed up and restored in a network-isolated container during Stage 2L. No migration was applied to that persistent database.

## Launch gates

1. Complete external email delivery and abuse verification and test the actual HTTPS edge.
2. Establish backup encryption, retention, off-host storage and recovery objectives. Exercise financial reconciliation and failover under realistic load.
3. Review data retention, privacy disclosures, account lifecycle and operational access. Run an independent security review.
4. Obtain the exact market-data permissions described in public-launch.md before enabling public provider access or any real-price simulated execution.
