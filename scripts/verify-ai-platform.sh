#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -f docker-compose.ai.yml ps
docker compose -f docker-compose.ai.yml exec -T redis redis-cli ping | grep -q PONG
docker compose -f docker-compose.ai.yml exec -T redis-stack redis-cli ping | grep -q PONG
docker compose -f docker-compose.ai.yml exec -T mysql sh -c 'mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent'
curl --fail --silent http://127.0.0.1:${AI_MINIO_PORT:-9000}/minio/health/live >/dev/null
mvn -q -DskipTests compile
echo "AI platform dependencies and compilation verified."
