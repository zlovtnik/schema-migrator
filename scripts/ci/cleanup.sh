#!/usr/bin/env bash
set -euo pipefail
source scripts/ci/common.sh
# The EXIT trap is stage-scoped. This fallback runs after all parallel stages,
# including cancellation, and only removes containers belonging to this build.
trap - EXIT
cleanup_endpoint() {
  local containers
  containers="$(docker_cmd ps -aq --filter "label=org.ssl-proxy.ci.run=$CI_SCOPE")" || return 0
  if [ -n "$containers" ]; then
    # shellcheck disable=SC2086
    docker_cmd rm --force $containers >/dev/null 2>&1 || true
  fi
}
# Standalone validation uses the inherited Docker endpoint.
cleanup_endpoint
if [ -n "${DOCKER_CONTEXT_NAME:-}" ] && \
   docker context inspect "$DOCKER_CONTEXT_NAME" >/dev/null 2>&1; then
  CI_USE_DOCKER_CONTEXT=true
  cleanup_endpoint
  if [ -n "${BUILDER:-}" ]; then
    docker_cmd buildx prune --builder "$BUILDER" --filter until=168h \
      --reserved-space 20GB --force || echo "buildx cache prune failed; continuing"
  fi
else
  echo "skipped: CI Docker context is unavailable"
fi
