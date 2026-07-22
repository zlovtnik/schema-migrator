#!/usr/bin/env bash
set -euo pipefail

public_host="${1:?usage: deploy/instance/render-instance-config.sh PUBLIC_HOSTNAME}"
if [[ "${public_host}" == http://* || "${public_host}" == https://* ]]; then
  public_origin="${PUBLIC_ORIGIN:-${public_host}}"
else
  public_origin="${PUBLIC_ORIGIN:-https://${public_host}}"
fi
public_hostname="${PUBLIC_HOSTNAME:-${public_origin#https://}}"
public_hostname="${public_hostname#http://}"
public_hostname="${public_hostname%%/*}"
keycloak_public_url="${KEYCLOAK_PUBLIC_URL:?KEYCLOAK_PUBLIC_URL must point to the external identity provider}"
cors_origins="${public_origin},http://localhost:5173,http://127.0.0.1:5173,http://localhost:4174,http://127.0.0.1:4174"

mkdir -p deploy/instance

tmp_env="$(mktemp)"
cp .env "${tmp_env}"

escape_sed_regex() {
  printf '%s' "$1" | sed 's/[][\.*^$/]/\\&/g; s/#/\\#/g'
}

escape_sed_repl() {
  printf '%s' "$1" | sed 's/[\\&/#]/\\&/g'
}

upsert_env() {
  local key="$1"
  local value="$2"
  local safe_key_pattern
  local safe_key_repl
  local safe_value
  safe_key_pattern="$(escape_sed_regex "${key}")"
  safe_key_repl="$(escape_sed_repl "${key}")"
  safe_value="$(escape_sed_repl "${value}")"
  if grep -q "^${safe_key_pattern}=" "${tmp_env}"; then
    sed "s#^${safe_key_pattern}=.*#${safe_key_repl}=${safe_value}#" "${tmp_env}" > "${tmp_env}.next"
    mv "${tmp_env}.next" "${tmp_env}"
  else
    printf '%s=%s\n' "${key}" "${value}" >> "${tmp_env}"
  fi
}

KEYCLOAK_PUBLIC_URL="${keycloak_public_url}" PUBLIC_ORIGIN="${public_origin}" \
  envsubst '${KEYCLOAK_PUBLIC_URL} ${PUBLIC_ORIGIN}' \
  < deploy/instance/runtime-config.template.js \
  > deploy/instance/runtime-config.js

upsert_env "PUBLIC_HOSTNAME" "${public_hostname}"
upsert_env "BEDROCK_CORS_ORIGINS" "${cors_origins}"
upsert_env "BEDROCK_KEYCLOAK_ENABLED" "true"
upsert_env "BEDROCK_KEYCLOAK_ISSUER" "${BEDROCK_KEYCLOAK_ISSUER:-${keycloak_public_url}/realms/middleware}"
upsert_env "BEDROCK_KEYCLOAK_JWKS_URI" "${BEDROCK_KEYCLOAK_JWKS_URI:-${keycloak_public_url}/realms/middleware/protocol/openid-connect/certs}"
upsert_env "BEDROCK_KEYCLOAK_CLIENT_ID" "${BEDROCK_KEYCLOAK_CLIENT_ID:-bedrock-ui}"
upsert_env "BEDROCK_KEYCLOAK_AUDIENCE" "${BEDROCK_KEYCLOAK_AUDIENCE:-bedrock-ui}"
upsert_env "VITE_KEYCLOAK_URL" "${keycloak_public_url}"
upsert_env "VITE_KEYCLOAK_REALM" "${VITE_KEYCLOAK_REALM:-middleware}"
upsert_env "VITE_KEYCLOAK_CLIENT_ID" "${VITE_KEYCLOAK_CLIENT_ID:-bedrock-ui}"
upsert_env "VITE_KEYCLOAK_REDIRECT_URI" "${public_origin}/callback"

mv "${tmp_env}" .env

echo "Rendered instance config for ${public_origin} with external identity provider ${keycloak_public_url}"
