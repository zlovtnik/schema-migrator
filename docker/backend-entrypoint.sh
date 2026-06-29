#!/usr/bin/env bash
set -euo pipefail

mkdir -p /home/schema/.sbt /home/schema/.ivy2 /home/schema/.cache/coursier
chown -R schema:schema /home/schema/.sbt /home/schema/.ivy2 /home/schema/.cache

exec runuser -u schema -- "$@"
