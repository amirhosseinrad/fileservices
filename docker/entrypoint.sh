#!/usr/bin/env bash
set -euo pipefail

# Resolve the PostgreSQL version from the default Debian/Ubuntu packaging layout.
PG_VERSION="$(ls /etc/postgresql | head -n 1 || true)"
if [[ -z "${PG_VERSION}" ]]; then
  echo "Unable to determine installed PostgreSQL version." >&2
  exit 1
fi

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-file_services}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-Amir@123456}"
DB_SCHEMA="${DB_SCHEMA:-public}"

PG_CONF="/etc/postgresql/${PG_VERSION}/main/postgresql.conf"
PG_HBA="/etc/postgresql/${PG_VERSION}/main/pg_hba.conf"

# Allow external connections and custom port configuration.
if [[ -f "${PG_CONF}" ]]; then
  sed -ri "s|^#?listen_addresses.*|listen_addresses = '*'|" "${PG_CONF}"
  if ! grep -q '^listen_addresses' "${PG_CONF}"; then
    echo "listen_addresses = '*'" >> "${PG_CONF}"
  fi

  sed -ri "s|^#?port.*|port = ${DB_PORT}|" "${PG_CONF}"
  if ! grep -q '^port = ' "${PG_CONF}"; then
    echo "port = ${DB_PORT}" >> "${PG_CONF}"
  fi
fi

if [[ -f "${PG_HBA}" ]]; then
  if ! grep -q "0.0.0.0/0" "${PG_HBA}"; then
    echo "host all all 0.0.0.0/0 scram-sha-256" >> "${PG_HBA}"
  fi
  if ! grep -q "::/0" "${PG_HBA}"; then
    echo "host all all ::/0 scram-sha-256" >> "${PG_HBA}"
  fi
fi

escape_squotes() {
  local input="$1"
  printf '%s' "${input//\'/''}"
}

escape_dquotes() {
  local input="$1"
  printf '%s' "${input//\"/\\\"}"
}

run_as_postgres() {
  local cmd="$1"
  su - postgres -s /bin/bash -c "${cmd}"
}

run_sql() {
  local sql="$1"
  local escaped
  escaped=$(escape_dquotes "${sql}")
  run_as_postgres "psql -v ON_ERROR_STOP=1 -c \"${escaped}\""
}

run_sql_in_db() {
  local database="$1"
  local sql="$2"
  local escaped
  escaped=$(escape_dquotes "${sql}")
  run_as_postgres "psql -v ON_ERROR_STOP=1 -d \"${database}\" -c \"${escaped}\""
}

run_query() {
  local query="$1"
  local escaped
  local output
  escaped=$(escape_dquotes "${query}")
  output=$(run_as_postgres "psql -Atqc \"${escaped}\"" || true)
  printf '%s' "${output}" | tr -d '\n\r\t '
}

run_query_in_db() {
  local database="$1"
  local query="$2"
  local escaped
  local output
  escaped=$(escape_dquotes "${query}")
  output=$(run_as_postgres "psql -d \"${database}\" -Atqc \"${escaped}\"" || true)
  printf '%s' "${output}" | tr -d '\n\r\t '
}

# Start the default PostgreSQL cluster in the background so the application can connect.
echo "Starting PostgreSQL cluster ${PG_VERSION}/main ..."
pg_ctlcluster "${PG_VERSION}" main start

# Ensure the configured database user exists and has the expected password.
escaped_password="$(escape_squotes "${DB_PASSWORD}")"
if [[ "${DB_USER}" == "postgres" ]]; then
  run_sql "ALTER ROLE \"${DB_USER}\" WITH LOGIN PASSWORD '${escaped_password}';"
else
  ROLE_EXISTS=$(run_query "SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}';")
  if [[ -z "${ROLE_EXISTS}" ]]; then
    run_sql "CREATE ROLE \"${DB_USER}\" WITH LOGIN PASSWORD '${escaped_password}';"
  else
    run_sql "ALTER ROLE \"${DB_USER}\" WITH LOGIN PASSWORD '${escaped_password}';"
  fi
fi

# Ensure the target database exists and is owned by the configured user.
DB_EXISTS=$(run_query "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}';")
if [[ -z "${DB_EXISTS}" ]]; then
  echo "Creating database '${DB_NAME}' owned by '${DB_USER}' ..."
  run_sql "CREATE DATABASE \"${DB_NAME}\" OWNER \"${DB_USER}\";"
fi

# Ensure the requested schema exists inside the database and is owned by the user.
if [[ -n "${DB_SCHEMA}" ]]; then
  SCHEMA_EXISTS=$(run_query_in_db "${DB_NAME}" "SELECT 1 FROM information_schema.schemata WHERE schema_name='${DB_SCHEMA}';")
  if [[ -z "${SCHEMA_EXISTS}" ]]; then
    echo "Creating schema '${DB_SCHEMA}' in database '${DB_NAME}' ..."
    run_sql_in_db "${DB_NAME}" "CREATE SCHEMA IF NOT EXISTS \"${DB_SCHEMA}\" AUTHORIZATION \"${DB_USER}\";"
  fi
fi

# --- MinIO Auto-Start ---
#echo "Checking for MinIO container..."
#if ! docker ps --format '{{.Names}}' | grep -q '^minio$'; then
#  echo "No MinIO container found. Launching one..."
#  docker run -d \
#    --name minio \
#    -p 9000:9000 -p 9001:9001 \
#    -e MINIO_ROOT_USER=${STORAGE_MINIO_ACCESS_KEY:-minioadmin} \
#    -e MINIO_ROOT_PASSWORD=${STORAGE_MINIO_SECRET_KEY:-minioadmin} \
#    quay.io/minio/minio server /data --console-address ":9001"
#else
#  echo "MinIO already running."
#fi

# Launch the Spring Boot application.
echo "Starting file services application ..."
export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}}"
export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-${DB_USER}}"
export SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-${DB_PASSWORD}}"

exec java ${JAVA_OPTS:-} -jar /app/app.jar
