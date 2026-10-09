#!/usr/bin/env bash
set -euo pipefail
source scripts/ci/common.sh
ci_prepare_publish
revision="$(git rev-parse HEAD)"
docker_cmd buildx build --builder "$BUILDER" --platform linux/amd64 \
  --file Dockerfile.backend --tag "$CI_REGISTRY/schema-migrator-backend:$revision" \
  --metadata-file artifacts/schema-migrator-backend.json --push .
docker_cmd buildx build --builder "$BUILDER" --platform linux/amd64 \
  --file frontend/Dockerfile --tag "$CI_REGISTRY/schema-migrator-ui:$revision" \
  --metadata-file artifacts/schema-migrator-ui.json --push .
