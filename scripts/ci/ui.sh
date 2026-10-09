#!/usr/bin/env bash
set -euo pipefail
source scripts/ci/common.sh
ci_install_cleanup
tar -cf - schema-migrator-ui | ci_run ui-1 --rm -i -w /workspace/schema-migrator-ui oven/bun:1.3.11 \
  sh -c 'mkdir -p /workspace && tar --no-same-owner -C /workspace -xf - && bun install --frozen-lockfile && bun run test && bun run build'
