#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ ${TAPE_BACKUP_RECIPIENT:-} =~ ^age1[a-z0-9]{58}$ ]] || { echo 'A standard age public recipient is required' >&2; exit 1; }
[[ -d /backups && -w /backups ]] || { echo 'Mount a private writable backup directory at /backups' >&2; exit 1; }
[[ ${PGDATABASE:-} == tape ]] || { echo 'Expected the Tape database' >&2; exit 1; }
temporary=$(mktemp -d /tmp/tape-backup.XXXXXXXX)
partial=''
cleanup() {
    case "$temporary" in /tmp/tape-backup.*) rm -rf -- "$temporary";; esac
    if [[ -n "$partial" ]]; then rm -f -- "$partial"; fi
}
trap cleanup EXIT
# Validate the recipient before reading data. Private identities never belong on the application host.
printf '' | age --encrypt -r "$TAPE_BACKUP_RECIPIENT" -o "$temporary/recipient-check.age"
version=$(psql -X -At --no-password -v ON_ERROR_STOP=1 -c 'SELECT max(version::integer) FROM flyway_schema_history WHERE success')
[[ "$version" == 12 ]] || { echo 'Unexpected Tape schema; review the backup tool for this release' >&2; exit 1; }
pg_dump --format=custom --no-password --file="$temporary/database.dump"
plain_sha=$(sha256sum "$temporary/database.dump" | cut -d ' ' -f 1)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
identifier=$(cat /proc/sys/kernel/random/uuid)
file="tape-$stamp-$identifier.dump.age"
partial="/backups/$file.partial"
[[ ! -e "$partial" && ! -e "/backups/$file" ]] || { echo 'Backup path already exists' >&2; exit 1; }
age --encrypt -r "$TAPE_BACKUP_RECIPIENT" -o "$partial" "$temporary/database.dump"
cipher_sha=$(sha256sum "$partial" | cut -d ' ' -f 1)
mv -n -- "$partial" "/backups/$file"
[[ ! -e "$partial" ]] || { echo 'Refusing to overwrite an existing backup' >&2; exit 1; }
partial=''
printf '{"version":1,"file":"%s","schemaVersion":12,"sha256":"%s","plainSha256":"%s","createdUtc":"%s"}\n' \
    "$file" "$cipher_sha" "$plain_sha" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "/backups/$file.json"
echo "Encrypted backup complete: $file"
# This reports encryption and copy integrity. A separate offline-key restore drill is still required.
