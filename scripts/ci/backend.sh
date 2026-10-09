#!/usr/bin/env bash
set -euo pipefail
source scripts/ci/common.sh
ci_install_cleanup
tar -cf - . | ci_run backend-1 --rm -i -w /workspace \
  -v /var/run/docker.sock:/var/run/docker.sock azul/zulu-openjdk:21 \
  sh -c 'tar --no-same-owner -xf - && sh /workspace/scripts/ci/tasks/backend-1.sh'
