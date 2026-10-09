#!/usr/bin/env bash
# Kept identical in the umbrella and standalone repositories; see CI tests.
# Source from repository-root CI scripts.
set -euo pipefail

CI_SCOPE="jenkins-$(printf '%s' "${JOB_NAME:-local}:${BUILD_NUMBER:-0}:${WORKSPACE:-$PWD}" | cksum | awk '{print $1}')"
CI_STAGE="${0##*/}"
CI_STAGE="${CI_STAGE%.sh}"

docker_cmd() {
  if [ "${CI_USE_DOCKER_CONTEXT:-false}" = true ]; then
    env -u DOCKER_HOST -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
      DOCKER_CONTEXT="$DOCKER_CONTEXT_NAME" docker "$@"
  else
    docker "$@"
  fi
}

ci_container_name() {
  printf '%s-%s\n' "$CI_SCOPE" "$1"
}

ci_run() {
  local task="$1"
  shift
  docker_cmd run --name "$(ci_container_name "$task")" \
    --label "org.ssl-proxy.ci.run=$CI_SCOPE" \
    --label "org.ssl-proxy.ci.stage=$CI_STAGE" "$@"
}

ci_cleanup() {
  local containers
  # Labels work even when ci_run is the subshell on the right of a tar pipe.
  containers="$(docker_cmd ps -aq \
    --filter "label=org.ssl-proxy.ci.run=$CI_SCOPE" \
    --filter "label=org.ssl-proxy.ci.stage=$CI_STAGE")" || return 0
  if [ -n "$containers" ]; then
    # Docker IDs contain no spaces; pass each ID as its own argument.
    # shellcheck disable=SC2086
    docker_cmd rm --force $containers >/dev/null 2>&1 || true
  fi
}
ci_install_cleanup() {
  trap ci_cleanup EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

ci_refresh_context() {
  : "${DOCKER_CONTEXT_NAME:?Docker context name is required}"
  : "${DOCKER_HOST:?Docker host is required}"
  : "${DOCKER_CERT_PATH:?Docker client certificate path is required}"
  if docker context inspect "$DOCKER_CONTEXT_NAME" >/dev/null 2>&1; then
    docker context rm --force "$DOCKER_CONTEXT_NAME" >/dev/null
  fi
  docker context create "$DOCKER_CONTEXT_NAME" \
    --docker "host=$DOCKER_HOST,ca=$DOCKER_CERT_PATH/ca.pem,cert=$DOCKER_CERT_PATH/cert.pem,key=$DOCKER_CERT_PATH/key.pem" >/dev/null
  CI_USE_DOCKER_CONTEXT=true
  export CI_USE_DOCKER_CONTEXT
}

ci_check_inotify() {
  local inotify_instances_path=/proc/sys/fs/inotify/max_user_instances
  local required_inotify_instances=1024 current_inotify_instances
  local recovery_command='docker compose -f docker-compose.ci.yaml up -d --no-deps --force-recreate jenkins-docker'
  current_inotify_instances="$(cat "$inotify_instances_path")"
  case "$current_inotify_instances" in
    ''|*[!0-9]*) echo "invalid inotify capacity: $current_inotify_instances" >&2; echo "$recovery_command" >&2; return 1 ;;
  esac
  [ "$current_inotify_instances" -ge "$required_inotify_instances" ] || {
    echo "at least $required_inotify_instances inotify instances are required" >&2
    echo "$recovery_command" >&2
    return 1
  }
}

ci_prepare_publish() {
  test -n "${CI_REGISTRY:-}"
  test "${BRANCH_NAME:-}" = main
  ci_check_inotify
  mkdir -p artifacts
  ci_refresh_context
  printf '[registry."%s"]\n  http = true\n  insecure = true\n' "$CI_REGISTRY" > artifacts/buildkitd.toml
  if ! docker_cmd buildx inspect "$BUILDER" >/dev/null 2>&1; then
    docker_cmd buildx create --name "$BUILDER" --driver docker-container \
      --driver-opt network=host --buildkitd-config artifacts/buildkitd.toml >/dev/null
  fi
  docker_cmd buildx inspect "$BUILDER" --bootstrap >/dev/null
}
