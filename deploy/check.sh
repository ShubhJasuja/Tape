#!/usr/bin/env bash
# Operator cron check on the approved Linux host. It never prints resolved Compose secrets.
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: bash deploy/check.sh /private/beta.env /private/encrypted-backups' >&2; exit 1; }
repository=$(cd "$(dirname "$0")/.." && pwd)
compose=(docker compose --env-file "$1" -f "$repository/deploy/compose.beta.yaml")
for service in postgres app proxy; do
    container=$("${compose[@]}" ps -q "$service")
    [[ -n "$container" && $(docker inspect --format '{{.State.Running}}' "$container") == true ]] || { echo "Tape $service is not running" >&2; exit 1; }
    if [[ "$service" != proxy ]]; then
        [[ $(docker inspect --format '{{.State.Health.Status}}' "$container") == healthy ]] || { echo "Tape $service is unhealthy" >&2; exit 1; }
    fi
done
"${compose[@]}" exec -T app sh -c 'wget -q -O /dev/null --header="Authorization: Bearer $TAPE_OPS_TOKEN" http://127.0.0.1:8080/actuator/health/readiness'
origin=$("${compose[@]}" exec -T app printenv TAPE_PUBLIC_ORIGIN)
origin=${origin%/}
curl --fail --silent --show-error --max-time 10 "$origin/" > /dev/null
# Filesystem age checks are operational signals, not proof of off-host retention or recoverability.
latest=$(find "$2" -maxdepth 1 -type f -name '*.dump.age.json' -printf '%T@ %f\n' | sort -nr | sed -n '1p' | cut -d ' ' -f 2-)
[[ -n "$latest" ]] || { echo 'No completed encrypted backup manifest' >&2; exit 1; }
manifest="$2/$latest"
(( $(date +%s) - $(stat -c %Y "$manifest") < 129600 )) || { echo 'Encrypted backup is older than 36 hours' >&2; exit 1; }
file="${manifest%.json}"
[[ -s "$file" ]] || { echo 'Encrypted backup file missing or empty' >&2; exit 1; }
# Strictly compare the ciphertext checksum without logging its payload.
expected=$(sed -n 's/.*"sha256":"\([a-f0-9]\{64\}\)".*/\1/p' "$manifest")
[[ "$expected" =~ ^[a-f0-9]{64}$ && $(sha256sum "$file" | cut -d ' ' -f 1) == "$expected" ]] || { echo 'Encrypted backup checksum mismatch' >&2; exit 1; }
echo 'Tape readiness, HTTPS and recent local encrypted backup passed; confirm off-host replication separately'
