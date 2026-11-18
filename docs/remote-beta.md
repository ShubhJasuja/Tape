# Make Tape available to invited testers

PR #7 was reviewed and merged as `47ad971c811b31906ecbb65e4f5baa62068c4119`. Fresh local verification passed all 114 backend/PostgreSQL/Chrome tests and the React build; [main CI passed](https://github.com/ShubhJasuja/Tape/actions/runs/44258576167). No critical identity/accounting issue was found. This follow-up changes deployment/recovery tooling only. No new application stage, database migration, trading algorithm or frontend feature is introduced.

**Current boundary:** no approved remote host, DNS name, Brevo credentials or off-host backup destination has been provided. Nothing has been uploaded to a server, bought or published. SMTP delivery to a real mailbox, public DNS/certificates, external reachability and off-host retention are unverified. The existing local PostgreSQL V12 database and Stage 2M backups remain untouched.

## 1. Choose and approve the destination

Use one Linux machine with Docker Engine and Compose, ideally 4 GiB RAM. Run the existing monolith, PostgreSQL 17 and Caddy together. Point an owned subdomain's A record at the approved IPv4 address. Do not add AAAA until IPv6 has been tested. No managed database, Redis, Kubernetes or registry is required.

At the hosting firewall allow SSH only from the administrator's addresses, and standard TCP 80/443 for Caddy. Allow outbound DNS, package/image downloads, ACME HTTPS and Brevo TCP 587. Never publish PostgreSQL, application port 8080, Docker's API or operations endpoints. Docker's published ports can bypass UFW rules; do not rely on UFW alone. See [Docker's firewall documentation](https://docs.docker.com/engine/network/packet-filtering-firewalls/). A public-facing login page with closed onboarding is a closed invited beta; IP/VPN restrictions are an optional additional operator policy.

## 2. Configure secrets, sender and invitations privately

Copy `deploy/beta-config.example` to an administrator-owned file **outside** the checkout, such as `/srv/tape-secrets/beta.env`. Restrict the directory to 0700 and the file to 0600. Replace every `SET_*` value in a private editor. Never paste values into chat, GitHub, screenshots or shared logs. Use independent random database owner/runtime passwords, a persistent Base64-encoded 32-byte identity-mail key, and a random 32–128-character URL-safe operations token. Preserve the same identity key and credentials across restarts. The application receives only its runtime password; owner credentials are confined to PostgreSQL and explicit maintenance jobs.

For the approved remote host set these non-secret settings, substituting your actual name/reviewed commit:

```text
TAPE_BETA_HOST=beta.your-owned-domain.tld
TAPE_BETA_BIND=0.0.0.0
TAPE_BETA_HTTP_PORT=80
TAPE_BETA_HTTPS_PORT=443
TAPE_PUBLIC_ORIGIN=https://beta.your-owned-domain.tld
TAPE_IMAGE_TAG=tape-beta:REVIEWED_COMMIT
TAPE_REGISTRATION_MODE=ALLOWLIST
TAPE_REGISTRATION_ALLOWLIST=your-controlled-test-mailbox@your-domain.tld
```

Start with only your controlled mailbox, then add invited addresses and recreate the application when ready. `CLOSED` disables new registrations; existing verified accounts can still sign in and recover. Removing an address from the registration allowlist does not revoke an existing account. `PUBLIC` is rejected by the private-beta configuration check. Each new identity retains independent virtual funds. Practice prices remain explicitly synthetic; provider access and real-price fills stay disabled in Compose and in the server's non-local permission policy.

Complete the offline key/public-recipient setup in section 4 before remote preflight; the private identity must stay off the application host.

Keep Brevo. In its **Settings → Senders, Domains, IPs → Domains**, add the domain/subdomain used in your sender address. Copy the account-specific ownership TXT and DKIM TXT/two CNAME records to DNS. Set or update exactly one DMARC record using your existing domain policy; check Brevo's required reporting address/settings. Do not overwrite another mail sender's configuration. Shared-IP domain authentication does not require adding Brevo SPF/MX records; dedicated-IP requirements differ. Follow [Brevo's domain guide](https://help.brevo.com/hc/en-us/articles/12163873383186-Authenticate-your-domain-with-Brevo-Brevo-code-DKIM-DMARC) and verify the domain status in its dashboard. Exact DNS record values must come from your account; none are invented here.

Add and verify the transactional sender and complete any Brevo account/sending approval. On the **SMTP** settings page obtain the SMTP login and generate an SMTP key. Set `TAPE_SMTP_USER`, `TAPE_SMTP_PASSWORD` (SMTP key, not API key), and `TAPE_MAIL_FROM` in the private file. The existing `brevo` profile uses `smtp-relay.brevo.com:587`, authenticated STARTTLS, hostname verification and five-second timeouts. See [official SMTP setup](https://help.brevo.com/hc/en-us/articles/7924908994450-Send-transactional-emails-using-Brevo-SMTP). No provider SDK or subscription is added.

## 3. Validate and start only after publication approval

Check out the reviewed release on the approved machine. Use the same commit in the image tag. The default Compose values still bind to loopback at 8443/8088; merely checking the configuration starts no services and obtains no certificate.

```bash
set -euo pipefail
envfile=/srv/tape-secrets/beta.env
compose=(docker compose --env-file "$envfile" -f deploy/compose.beta.yaml)
"${compose[@]}" --profile maintenance config --format json |
  docker run --rm -i --network none --mount "type=bind,source=$PWD/scripts,target=/checks,readonly" \
    node:22.23.1-alpine node /checks/check-beta-config.mjs --remote
"${compose[@]}" build app
"${compose[@]}" up -d postgres
"${compose[@]}" --profile maintenance run --rm migrate
# Public listeners start here. Run only after approving this host and ingress.
"${compose[@]}" up -d --no-build app proxy
```

The preflight prints only validation status. Never print/tee resolved Compose JSON: it includes secrets. It rejects missing/placeholder secrets, public onboarding, mismatched origins/ports/images, privileged/host-network containers, published private services and enabled providers. It validates syntax and isolation, not DNS ownership, SMTP delivery or certificate issuance.

The commands initialize a **new, separate beta volume**. They do not copy, replace or reset the local Tape database. To retain existing local accounts/history remotely, restore a verified V12 archive into an empty destination first, preserve IDs and credential hashes, create the destination's unprivileged runtime role with its configured password, and then run the V12 maintenance/grants check. Keep the source and its backups intact. Do not mount the existing local volume into a new project or use `down -v` on real data. Older schemas require the existing backup-gated migration procedure, not `init`.

Caddy obtains and renews the domain certificate automatically when DNS and ports permit [ACME validation](https://caddyserver.com/docs/automatic-https). Its persistent `/data` volume contains certificates/keys. Only the fixed Caddy IP is trusted for forwarding headers; the app and PostgreSQL have no host ports. The proxy blocks `/actuator*` and `/internal*`. Do not add another proxy/CDN or widen header trust without checking its actual network path.

Verify `curl --fail https://YOUR_HOST/` without `-k`, check that operations URLs return 404, confirm Secure/HttpOnly cookies, and inspect `docker compose ... ps` for healthy app/database containers. Test from a second network before claiming remote access. Container readiness checks configuration and database access, **not** actual SMTP deliverability.

## 4. Encrypted off-host backup and recovery

The one-off `backup` maintenance job uses PostgreSQL 17 plus the standard [age encryption tool](https://github.com/FiloSottile/age). It stores only ciphertext and a checksum manifest in the mounted output directory. Plaintext exists temporarily in bounded container tmpfs and is removed when the job exits. It never runs in the application or exposes a port. Keep the age private identity offline, on an encrypted administrator device/password manager. Put only its public recipient in `TAPE_BACKUP_RECIPIENT`.

Generate the identity on a trusted recovery machine with `age-keygen -o tape.identity`, back it up offline, obtain its public recipient with `age-keygen -y tape.identity`, and record the recipient privately in the beta env file. Do not regenerate an existing identity or lose the old one. Build the backup tool and create an output directory writable by UID/GID 10001:

```bash
"${compose[@]}" --profile maintenance build backup
sudo install -d -m 700 -o 10001 -g 10001 /srv/tape-backups
"${compose[@]}" --profile maintenance run --rm --no-deps \
  --volume /srv/tape-backups:/backups backup
```

Run administrative checks/transfers with permission to read UID 10001-owned files; do not make backups world-readable. The backup tmpfs limit is 256 MiB; increase it after a memory review if the compressed dump outgrows it, and alert on failures. Transfer both `.dump.age` and `.dump.age.json` files to an independently controlled off-host destination over verified SSH/SFTP or protected object storage. Verify destination SHA-256 matches the manifest. Restrict retention/deletion rights so a compromised application host cannot erase every copy. No remote destination or credentials are configured here, so no transfer was performed. Age protects confidentiality and ciphertext integrity; unsigned checksums do not establish publisher identity or replace storage access controls.

Schedule the backup plus transfer nightly, fail/alert if either exits nonzero, keep multiple daily and weekly copies off-host, and rehearse restoration on a separate machine. A 24-hour backup interval implies up to a 24-hour RPO; measure actual restore time rather than claiming an RTO. Back up the private env file and Caddy configuration securely and separately; a database dump does not contain role passwords or the identity-mail key. Never commit these files or put the age private identity on the deployment host.

Recovery uses a disposable PostgreSQL 17 container with no network/ports: mount ciphertext and the private identity read-only, run `age --decrypt -i /identity -o /tmp/restored.dump /input/FILE.dump.age`, compare `sha256sum` to `plainSha256`, restore with `pg_restore --exit-on-error --no-owner --no-acl`, and run `pg_amcheck`. Create `tape_runtime NOLOGIN` in that isolated copy and run the application image's `DatabaseMaintenance init /opt/tape` using the restored owner's connection to apply/validate grants and reconcile financial books. Compare restored table counts. Never run a restored copy's web server/workers against the original database. The automated `Test-EncryptedBackup.ps1` performs this drill with a disposable test key, verifies tampered ciphertext is rejected, and deletes only its own fixture directory/container. It does not create a retained real backup.

For actual recovery, retain the original/failing database, restore into a **new** destination, validate source counts/accounting and release compatibility, recreate the destination runtime LOGIN credentials, then perform an approved connection cutover. Existing V12 initialization/grants checks create no new financial events. Do not reverse Flyway migrations or restore over the original. Protect every old backup/key until the new destination has been verified.

## 5. Monitoring, restart and the first invited journey

Docker healthchecks and bounded logs are already configured. On the approved Linux host, run `sudo bash deploy/check.sh /srv/tape-secrets/beta.env /srv/tape-backups` every five minutes with root cron/systemd and send nonzero exits to an administrator-controlled alert destination. It checks app/database/container state, authenticated internal readiness, certificate-verified HTTPS, and the checksum/age of the latest local encrypted backup (36-hour threshold). Configure log rotation/alert delivery and a separate external uptime check. Off-host transfer status, mail bounces/quotas and disk space still require operator monitoring; no external alert account is assumed. Inspect queue retry/unreadable metrics and reconciliation failures through container-loopback operations access only.

For a restart, `docker compose ... restart app` preserves JDBC sessions and PostgreSQL data. For an upgrade, retain the old image tag/env/config, take and verify an encrypted off-host backup, gracefully stop the app/proxy, review any schema changes, build the approved commit under a new tag, run the maintenance validation, then recreate the app/proxy and check readiness/TLS. `stop` gets 40 seconds for the application's 30-second graceful shutdown. This follow-up has no new migrations; a same-schema image rollback can retain the database. Any future schema rollback needs a reviewed forward fix or restore into a new destination, with explicit treatment of trades since the backup.

Use the initially allowlisted mailbox for the real journey: register, receive the verification message, inspect received DKIM/DMARC/SPF results, consume the single-use link, choose a password, sign in, check independent virtual funds, buy an equity, open/close an option and a future, inspect history/positions/P&L, sign out/in, reset the password, and confirm old sessions fail. Check session persistence after restarting the app. Keep registration CLOSED again if any gate fails. Only then add the remaining invited emails and recreate the app. No real-market quote or fill is enabled by these steps.

**Manual actions left:** approve a host/domain and ingress; configure private credentials, sender DNS and invitations; establish an offline recovery key/off-host retention and alerts; approve/start the reviewed release and complete the real-mail/second-network journey. The repository can automate local validation, but cannot truthfully claim those host/account checks before they exist.

## Verification recorded on 2026-11-18

- PR #7 review: 114 backend/PostgreSQL/Chrome tests passed, zero failures/errors/skips; React build passed. The first attempt could not find Docker's stopped engine; after Docker started, the complete suite passed. Main CI passed after merge.
- Follow-up configuration suite: 6 tests passed, including secret redaction, closed/invited onboarding, origin/port alignment, release images and private-service isolation.
- Extended Chrome journey: passed with explicit equity/options/futures order history, positions and P&L checks, verification/recovery emails through local STARTTLS, two-node sessions, restart and password-reset revocation. Six verified users also completed 180 portfolio reads at concurrency 18 with zero failures (56.47 requests/second, p95 583.3 ms). This is a local smoke/load measurement.
- Actual local Compose: passed using custom 18443/18088 ports, Caddy's exported disposable CA, hostname/certificate verification, Secure/HttpOnly cookies, blocked operations URLs and closed registration. Windows Schannel used best-effort revocation only for this private test CA, which has no CRL service; chain/hostname checks stayed enabled. No `--insecure` option is used by the deployment test.
- Encrypted recovery: a separately funded fixture account was backed up with age, decrypted to a byte-identical archive and restored into an isolated PostgreSQL 17 instance. Tampered ciphertext was rejected; all public table counts matched the stopped source; heap/index checks and existing financial reconciliation passed. The test key and its temporary files were deleted after verification. Off-host transfer/retention were not attempted.
- The final encrypted recovery helper also passed against a read-only snapshot of the actual local V12 source, with all public table counts matched and restored journals reconciled in a no-network disposable copy. The existing retained backups were not changed; the drill's key/ciphertext were temporary fixtures.
- Actual Docker restart recovered readiness and preserved the JDBC-backed CSRF session. Original database: still V12, 2 trading accounts, 200,000,000 paise in wallets and the same total in the immutable cash journal. No new migration or original financial-data change was performed.

Reproduce with `node --test scripts/check-beta-config.test.mjs`, the existing full Maven/Chrome suite, local builds of `deploy/Dockerfile` and `deploy/backup.Dockerfile`, and `./scripts/Test-BetaDeployment.ps1 -EncryptedRecovery`. GitHub CI now runs all of these checks. No application/backup image is pushed to a registry. Real SMTP/DNS/host/retention and alert delivery remain operator gates.

One initial push CI run failed in the Chrome journey without exposing its diagnostic file; the identical-head PR run passed all checks, including deployment/recovery. The harness now waits for a complete DevTools discovery file and emits bounded, redacted startup/journey diagnostics. A local repeat passed. The original failure's precise cause was not observable, so it is not attributed to a confirmed application defect. Final-head CI results are linked in the PR.
