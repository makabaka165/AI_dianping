#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker info >/dev/null
docker compose -f docker-compose.ai.yml up -d --wait
echo "AI infrastructure is ready."
