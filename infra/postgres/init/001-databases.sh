#!/usr/bin/env bash
set -Eeuo pipefail

create_user_and_database() {
  local name="$1"
  local variable="$2"
  : "${!variable:?Set $variable}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set="app_db=${name}_db" --set="app_user=$name" --set="app_password=${!variable}" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user')\gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'app_db', :'app_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'app_db')\gexec
REVOKE CONNECT, TEMPORARY ON DATABASE :"app_db" FROM PUBLIC;
SQL
}

create_user_and_database identity IDENTITY_DB_PASSWORD
create_user_and_database proposal PROPOSAL_DB_PASSWORD
create_user_and_database project PROJECT_DB_PASSWORD
create_user_and_database document DOCUMENT_DB_PASSWORD
create_user_and_database reporting REPORTING_DB_PASSWORD
create_user_and_database audit AUDIT_DB_PASSWORD
create_user_and_database keycloak KEYCLOAK_DB_PASSWORD

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
REVOKE CONNECT, TEMPORARY ON DATABASE postgres, template1 FROM PUBLIC;
SQL
