#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

if [[ ! -f deploy/keycloak/middleware-realm.json ]]; then
  echo "missing deploy/keycloak/middleware-realm.json; run deploy/instance/render-instance-config.sh PUBLIC_IP first" >&2
  exit 1
fi

if [[ ! -f deploy/instance/runtime-config.js ]]; then
  echo "missing deploy/instance/runtime-config.js; run deploy/instance/render-instance-config.sh PUBLIC_IP first" >&2
  exit 1
fi

docker compose up -d --build
