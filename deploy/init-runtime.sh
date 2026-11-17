#!/bin/sh
set -eu
# Runs only on an empty PostgreSQL volume. Existing runtime passwords are never reset.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=runtime_password="$TAPE_RUNTIME_PASSWORD" <<'SQL'
CREATE ROLE tape_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION PASSWORD :'runtime_password';
SQL
