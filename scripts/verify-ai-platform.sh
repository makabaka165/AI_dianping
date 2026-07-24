#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="${AI_VERIFY_COMPOSE_FILE:-docker-compose.ai.yml}"
APP_PORT="${AI_VERIFY_APP_PORT:-8081}"
MODEL_PORT="${AI_VERIFY_MODEL_PORT:-18080}"
COMPOSE_MYSQL_PORT="${AI_MYSQL_PORT:-3307}"
COMPOSE_REDIS_PORT="${AI_REDIS_PORT:-6381}"
COMPOSE_REDIS_STACK_PORT="${AI_REDIS_STACK_PORT:-6380}"
COMPOSE_MINIO_PORT="${AI_MINIO_PORT:-9000}"
APP_URL="http://127.0.0.1:${APP_PORT}"
MODEL_URL="http://127.0.0.1:${MODEL_PORT}"
VERIFY_PHONE="${AI_VERIFY_PHONE:-13686869696}"
TENANT_ID="${AI_VERIFY_TENANT_ID:-default}"
WORKSPACE_ID="${AI_VERIFY_WORKSPACE_ID:-default}"
RUN_STAMP="$(date +%s)-$$"
WORK_DIR="target/verify-ai-platform-${RUN_STAMP}"
APP_LOG="$WORK_DIR/application.log"
MODEL_LOG="$WORK_DIR/model-stub.log"
APP_PID=""
MODEL_PID=""
SSE_PID=""

mkdir -p "$WORK_DIR"

log() {
  printf '[verify-ai-platform] %s\n' "$*"
}

fail() {
  printf '[verify-ai-platform] ERROR: %s\n' "$*" >&2
  if [[ -f "$APP_LOG" ]]; then
    printf '%s\n' '--- application log (last 120 lines) ---' >&2
    tail -n 120 "$APP_LOG" >&2 || true
  fi
  exit 1
}

cleanup() {
  local pid
  for pid in "$APP_PID" "$MODEL_PID" "$SSE_PID"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT INT TERM

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is missing: $1"
}

assert_compose_loopback_port() {
  local service="$1"
  local container_port="$2"
  local expected_host_port="$3"
  local setting="$4"
  local endpoint
  endpoint="$(docker compose -f "$COMPOSE_FILE" port "$service" "$container_port" 2>/dev/null | head -n 1 || true)"
  [[ -n "$endpoint" ]] || \
    fail "Compose ${service}:${container_port} is not published; recreate it with ${setting}=${expected_host_port}"
  [[ "$endpoint" == "127.0.0.1:${expected_host_port}" ]] || \
    fail "Compose ${service}:${container_port} is published at ${endpoint}, expected 127.0.0.1:${expected_host_port}"
}

export_verified_endpoint() {
  local variable="$1"
  local expected="$2"
  local configured="${!variable:-}"
  if [[ -n "$configured" && "$configured" != "$expected" ]]; then
    fail "${variable}=${configured} does not target the verified Compose endpoint ${expected}"
  fi
  export "${variable}=${expected}"
}

for command in docker curl java mvn; do
  require_command "$command"
done

if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys; assert sys.version_info >= (3, 8)' >/dev/null 2>&1; then
  PYTHON_BIN=python3
elif command -v python >/dev/null 2>&1 && python -c 'import sys; assert sys.version_info >= (3, 8)' >/dev/null 2>&1; then
  PYTHON_BIN=python
else
  fail "Python 3.8 or newer is required for JSON assertions and the local model stub"
fi

json_value() {
  local path="$1"
  "$PYTHON_BIN" -c '
import json
import sys

value = json.load(sys.stdin)
for part in sys.argv[1].split("."):
    if not part:
        continue
    value = value[int(part)] if isinstance(value, list) else value[part]
if value is None:
    print("")
elif isinstance(value, bool):
    print(str(value).lower())
elif isinstance(value, (dict, list)):
    print(json.dumps(value, separators=(",", ":")))
else:
    print(value)
' "$path"
}

assert_json_agent_seed() {
  "$PYTHON_BIN" -c '
import json
import sys

payload = json.load(sys.stdin)
agents = payload.get("items", [])
if not any(item.get("id") == "agent-shop-consultant" and
           item.get("code") == "shop-consultant" and
           item.get("latestVersion") == 1 for item in agents):
    raise SystemExit("default shop-consultant seed was not returned by the Agent API")
'
}

assert_search_result() {
  "$PYTHON_BIN" -c '
import json
import sys

payload = json.load(sys.stdin)
results = payload.get("results", [])
if not results:
    raise SystemExit("hybrid retrieval returned no results")
if not payload.get("rerankMode"):
    raise SystemExit("hybrid retrieval did not report rerankMode")
'
}

assert_document_absent() {
  local document_id="$1"
  "$PYTHON_BIN" -c '
import json
import sys

payload = json.load(sys.stdin)
document_id = sys.argv[1]
if any(item.get("documentId") == document_id for item in payload.get("results", [])):
    raise SystemExit("deleted document was returned by hybrid retrieval")
' "$document_id"
}

minio_object_count() {
  docker compose -f "$COMPOSE_FILE" exec -T minio-init \
    mc ls --recursive --json "local/$MINIO_BUCKET" | "$PYTHON_BIN" -c '
import json
import sys

count = 0
for line in sys.stdin:
    if not line.strip():
        continue
    item = json.loads(line)
    if item.get("status") != "success" or item.get("type") != "file":
        raise SystemExit("unexpected MinIO listing response")
    count += 1
print(count)
'
}

wait_for_url() {
  local url="$1"
  local pid="$2"
  local label="$3"
  local attempts="${4:-120}"
  local attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl --silent --fail "$url" >/dev/null 2>&1; then
      return 0
    fi
    if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then
      fail "$label exited before becoming healthy"
    fi
    sleep 1
  done
  fail "$label did not become healthy at $url"
}

auth_curl() {
  curl --silent --show-error --fail \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "X-Tenant-Id: ${TENANT_ID}" \
    -H "X-Workspace-Id: ${WORKSPACE_ID}" \
    "$@"
}

wait_for_ingestion_published() {
  local job_id="$1"
  local job_status=""
  local job_response=""
  local job_error=""
  for _ in $(seq 1 120); do
    job_response="$(auth_curl "$APP_URL/api/v1/ingestion-jobs/$job_id")"
    job_status="$(printf '%s' "$job_response" | json_value status)"
    case "$job_status" in
      PUBLISHED) return 0 ;;
      FAILED|CANCELLED)
        job_error="$(printf '%s' "$job_response" | json_value errorCode)"
        fail "knowledge ingestion reached $job_status ($job_error)"
        ;;
    esac
    sleep 1
  done
  fail "knowledge ingestion did not become PUBLISHED"
}

log "checking Docker infrastructure"
docker compose -f "$COMPOSE_FILE" ps
assert_compose_loopback_port mysql 3306 "$COMPOSE_MYSQL_PORT" AI_MYSQL_PORT
assert_compose_loopback_port redis 6379 "$COMPOSE_REDIS_PORT" AI_REDIS_PORT
assert_compose_loopback_port redis-stack 6379 "$COMPOSE_REDIS_STACK_PORT" AI_REDIS_STACK_PORT
assert_compose_loopback_port minio 9000 "$COMPOSE_MINIO_PORT" AI_MINIO_PORT
docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli ping | grep -q PONG
docker compose -f "$COMPOSE_FILE" exec -T redis-stack redis-cli ping | grep -q PONG
docker compose -f "$COMPOSE_FILE" exec -T mysql sh -c \
  'mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent'
curl --fail --silent "http://127.0.0.1:${COMPOSE_MINIO_PORT}/minio/health/live" >/dev/null

EXPECTED_DB_URL="jdbc:mysql://127.0.0.1:${COMPOSE_MYSQL_PORT}/${AI_MYSQL_DATABASE:-hmdp}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
export_verified_endpoint DB_URL "$EXPECTED_DB_URL"
export_verified_endpoint REDIS_HOST 127.0.0.1
export_verified_endpoint REDIS_PORT "$COMPOSE_REDIS_PORT"
export_verified_endpoint VECTOR_REDIS_HOST 127.0.0.1
export_verified_endpoint VECTOR_REDIS_PORT "$COMPOSE_REDIS_STACK_PORT"
export_verified_endpoint MEMORY_REDIS_HOST 127.0.0.1
export_verified_endpoint MEMORY_REDIS_PORT "$COMPOSE_REDIS_PORT"
export_verified_endpoint MINIO_ENDPOINT "http://127.0.0.1:${COMPOSE_MINIO_PORT}"

log "building the executable backend"
mvn -q -DskipTests package
JAR_FILE="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | head -n 1)"
[[ -n "$JAR_FILE" ]] || fail "Spring Boot jar was not produced"

log "starting local OpenAI-compatible verification provider"
"$PYTHON_BIN" scripts/verify-openai-compatible-stub.py \
  --port "$MODEL_PORT" --dimension 1024 >"$MODEL_LOG" 2>&1 &
MODEL_PID=$!
wait_for_url "$MODEL_URL/health" "$MODEL_PID" "model stub" 30

export DB_USERNAME="${DB_USERNAME:-root}"
export DB_PASSWORD="${DB_PASSWORD:-${AI_MYSQL_ROOT_PASSWORD:-change_me_local}}"
export MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-${AI_MINIO_ROOT_USER:-local_minio_user}}"
export MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-${AI_MINIO_ROOT_PASSWORD:-change_me_local_minio}}"
export MINIO_BUCKET="${MINIO_BUCKET:-${AI_MINIO_BUCKET:-hmdp-ai}}"
export HMDP_SMS_MOCK_ENABLED=true
export HMDP_AI_QUOTA_ENABLED=false
export HMDP_AI_TASK_ENABLED=false
export RAG_ENABLED=false
export RAG_DATA_AUTO_IMPORT=false
export AI_CHAT_API_KEY="${AI_CHAT_API_KEY:-verify-local-key}"
export AI_EMBEDDING_API_KEY="${AI_EMBEDDING_API_KEY:-verify-local-key}"
export AI_VERIFY_KEY="${AI_VERIFY_KEY:-verify-local-key}"
export AI_CHAT_BASE_URL="$MODEL_URL/v1"
export AI_EMBEDDING_BASE_URL="$MODEL_URL/v1"

log "starting application and running Flyway migrations"
java -jar "$JAR_FILE" --server.port="$APP_PORT" >"$APP_LOG" 2>&1 &
APP_PID=$!
wait_for_url "$APP_URL/actuator/health" "$APP_PID" "application" 180

HEALTH_RESPONSE="$(curl --silent --show-error --fail "$APP_URL/actuator/health")"
[[ "$(printf '%s' "$HEALTH_RESPONSE" | json_value status)" == "UP" ]] || fail "application health is not UP"
FLYWAY_COUNT="$(docker compose -f "$COMPOSE_FILE" exec -T mysql sh -c \
  'mysql -N -s -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "select count(*) from flyway_schema_history where success=1"')"
[[ "$FLYWAY_COUNT" =~ ^[1-9][0-9]*$ ]] || fail "Flyway did not report successful migrations"

log "authenticating seeded workspace owner"
CODE_RESPONSE="$(curl --silent --show-error --fail -X POST \
  "$APP_URL/user/code?phone=${VERIFY_PHONE}")"
VERIFY_CODE="$(printf '%s' "$CODE_RESPONSE" | json_value data.verifyCode)"
[[ "$VERIFY_CODE" =~ ^[0-9]{6}$ ]] || fail "mock SMS endpoint did not expose a verification code"
LOGIN_RESPONSE="$(curl --silent --show-error --fail -X POST "$APP_URL/user/login" \
  -H 'Content-Type: application/json' \
  -d "{\"phone\":\"${VERIFY_PHONE}\",\"code\":\"${VERIFY_CODE}\"}")"
TOKEN="$(printf '%s' "$LOGIN_RESPONSE" | json_value data)"
[[ -n "$TOKEN" ]] || fail "login did not return a bearer token"

log "checking the default shop-consultant seed through the public API"
AGENTS_RESPONSE="$(auth_curl "$APP_URL/api/v1/agents?page=1&size=100")"
printf '%s' "$AGENTS_RESPONSE" | assert_json_agent_seed

log "creating a basic Agent Run"
RUN_RESPONSE="$(auth_curl -X POST "$APP_URL/api/v1/agent-runs" \
  -H 'Content-Type: application/json' \
  -d "{\"agentId\":\"shop-consultant\",\"agentVersion\":1,\"sessionId\":\"verify-${RUN_STAMP}\",\"input\":{\"text\":\"summarize this shop\",\"parts\":[],\"attachments\":[],\"referenceUris\":[]},\"responseMode\":\"STREAM\",\"metadata\":{}}")"
RUN_ID="$(printf '%s' "$RUN_RESPONSE" | json_value runId)"
[[ -n "$RUN_ID" ]] || fail "Agent Run creation did not return runId"
[[ "$(printf '%s' "$RUN_RESPONSE" | json_value agentDefinitionId)" == "agent-shop-consultant" ]] || \
  fail "Agent Run returned an inconsistent agentDefinitionId"
[[ "$(printf '%s' "$RUN_RESPONSE" | json_value agentCode)" == "shop-consultant" ]] || \
  fail "Agent Run returned an inconsistent agentCode"

RUN_STATUS=""
for _ in $(seq 1 60); do
  RUN_DETAIL="$(auth_curl "$APP_URL/api/v1/agent-runs/$RUN_ID")"
  RUN_STATUS="$(printf '%s' "$RUN_DETAIL" | json_value status)"
  case "$RUN_STATUS" in
    WAITING_FOR_USER) break ;;
    FAILED|CANCELLED) fail "basic Agent Run reached unexpected status $RUN_STATUS" ;;
  esac
  sleep 1
done
[[ "$RUN_STATUS" == "WAITING_FOR_USER" ]] || fail "basic Agent Run did not reach WAITING_FOR_USER"

log "checking SSE replay and Last-Event-ID reconnection"
SSE_HEADERS_1="$WORK_DIR/sse-first.headers"
SSE_EVENTS_1="$WORK_DIR/sse-first.events"
set +e
SSE_FIRST="$(auth_curl --no-buffer --max-time 4 -D "$SSE_HEADERS_1" \
  -H 'Last-Event-ID: 0' "$APP_URL/api/v1/agent-runs/$RUN_ID/events" 2>/dev/null)"
SSE_EXIT=$?
set -e
printf '%s\n' "$SSE_FIRST" >"$SSE_EVENTS_1"
[[ "$SSE_EXIT" -eq 0 || "$SSE_EXIT" -eq 28 ]] || fail "initial SSE request failed with exit code $SSE_EXIT"
grep -Eq '^HTTP/[^ ]+ 200' "$SSE_HEADERS_1" || fail "initial SSE request did not return HTTP 200"
LAST_EVENT_ID="$(awk '/^id:/{value=$0; sub(/^id:[[:space:]]*/, "", value); sub(/\r$/, "", value)} END{print value}' "$SSE_EVENTS_1")"
[[ "$LAST_EVENT_ID" =~ ^[0-9]+$ ]] || fail "SSE replay did not return sequenced events"

SSE_HEADERS_2="$WORK_DIR/sse-reconnect.headers"
SSE_EVENTS_2="$WORK_DIR/sse-reconnect.events"
SSE_ERROR_2="$WORK_DIR/sse-reconnect.error"
set +e
auth_curl --no-buffer --max-time 10 -D "$SSE_HEADERS_2" \
  -H "Last-Event-ID: $LAST_EVENT_ID" "$APP_URL/api/v1/agent-runs/$RUN_ID/events" \
  >"$SSE_EVENTS_2" 2>"$SSE_ERROR_2" &
SSE_PID=$!
set -e
sleep 1
CANCEL_RESPONSE="$(auth_curl -X POST "$APP_URL/api/v1/agent-runs/$RUN_ID/cancel")"
[[ "$(printf '%s' "$CANCEL_RESPONSE" | json_value status)" == "CANCELLED" ]] || \
  fail "Agent Run cancellation did not return CANCELLED"
set +e
wait "$SSE_PID"
SSE_EXIT=$?
set -e
SSE_PID=""
[[ "$SSE_EXIT" -eq 0 || "$SSE_EXIT" -eq 28 ]] || fail "reconnected SSE request failed with exit code $SSE_EXIT"
grep -Eq '^HTTP/[^ ]+ 200' "$SSE_HEADERS_2" || fail "reconnected SSE request did not return HTTP 200"
grep -Eq '^id:' "$SSE_EVENTS_2" || fail "SSE reconnection did not receive the cancellation event"
grep -Eq '^event:[[:space:]]*run\.cancelled' "$SSE_EVENTS_2" || \
  fail "SSE reconnection did not receive run.cancelled"
if awk -v last="$LAST_EVENT_ID" '
  /^id:/ {
    value=$0
    sub(/^id:[[:space:]]*/, "", value)
    sub(/\r$/, "", value)
    if ((value + 0) <= (last + 0)) bad=1
  }
  END { exit bad ? 0 : 1 }
' "$SSE_EVENTS_2"; then
  fail "SSE reconnection replayed an event at or before Last-Event-ID"
fi

log "creating an API-managed embedding model for ingestion verification"
MODEL_CODE="verify-embedding-${RUN_STAMP}"
MODEL_RESPONSE="$(auth_curl -X POST "$APP_URL/api/v1/model-profiles" \
  -H 'Content-Type: application/json' \
  -d "{\"code\":\"${MODEL_CODE}\",\"name\":\"Verification embedding model\",\"provider\":\"OPENAI_COMPATIBLE\",\"modelName\":\"verify-embedding\",\"baseUrl\":\"${MODEL_URL}/v1\",\"secretRef\":\"env:AI_VERIFY_KEY\",\"modelType\":\"EMBEDDING\",\"capabilitiesJson\":\"{\\\"streaming\\\":false,\\\"toolCalling\\\":false,\\\"jsonSchema\\\":false,\\\"vision\\\":false,\\\"longContext\\\":false}\",\"defaultParametersJson\":\"{}\",\"contextWindow\":8192,\"maxOutputTokens\":1,\"timeoutMs\":5000,\"retryPolicyJson\":\"{\\\"maxAttempts\\\":2,\\\"backoffMillis\\\":50}\",\"inputTokenPrice\":0,\"outputTokenPrice\":0,\"enabled\":true}")"
MODEL_PROFILE_ID="$(printf '%s' "$MODEL_RESPONSE" | json_value id)"
[[ -n "$MODEL_PROFILE_ID" ]] || fail "embedding Model Profile creation did not return id"

log "creating a DRAFT knowledge version and uploading a document"
KB_CODE="verify-kb-${RUN_STAMP}"
KB_RESPONSE="$(auth_curl -X POST "$APP_URL/api/v1/knowledge-bases" \
  -H 'Content-Type: application/json' \
  -d "{\"code\":\"${KB_CODE}\",\"name\":\"Verification knowledge base\",\"description\":\"Automated platform verification\"}")"
KB_ID="$(printf '%s' "$KB_RESPONSE" | json_value id)"
[[ -n "$KB_ID" ]] || fail "Knowledge Base creation did not return id"

KB_VERSION_RESPONSE="$(auth_curl -X POST "$APP_URL/api/v1/knowledge-bases/$KB_ID/versions" \
  -H 'Content-Type: application/json' \
  -d "{\"embeddingModelProfileId\":\"${MODEL_PROFILE_ID}\",\"embeddingDimension\":1024,\"chunkingPolicyJson\":\"{\\\"strategy\\\":\\\"RECURSIVE\\\",\\\"maxChars\\\":500,\\\"minChars\\\":40,\\\"overlapChars\\\":40,\\\"preserveHeading\\\":true}\",\"retrievalPolicyJson\":\"{\\\"vectorTopN\\\":10,\\\"lexicalTopN\\\":10,\\\"finalTopK\\\":5,\\\"maxContextCharsPerChunk\\\":800}\",\"changeNote\":\"Automated verification version\"}")"
[[ "$(printf '%s' "$KB_VERSION_RESPONSE" | json_value version)" == "1" ]] || \
  fail "Knowledge Base version 1 was not created"

VERIFY_DOCUMENT="$WORK_DIR/refund-policy.txt"
printf '%s\n' \
  'refund policy verification document' \
  'Customers may request a refund within thirty days when proof of purchase is available.' \
  'The service desk records each refund decision and preserves its supporting evidence.' \
  >"$VERIFY_DOCUMENT"
UPLOAD_RESPONSE="$(auth_curl -X POST \
  -F 'knowledgeBaseVersion=1' \
  -F 'title=Refund policy verification' \
  -F "file=@${VERIFY_DOCUMENT};type=text/plain" \
  "$APP_URL/api/v1/knowledge-bases/$KB_ID/documents")"
DOCUMENT_ID="$(printf '%s' "$UPLOAD_RESPONSE" | json_value documentId)"
JOB_ID="$(printf '%s' "$UPLOAD_RESPONSE" | json_value jobId)"
[[ -n "$DOCUMENT_ID" ]] || fail "knowledge upload did not return a document"
[[ -n "$JOB_ID" ]] || fail "knowledge upload did not return an ingestion job"
wait_for_ingestion_published "$JOB_ID"

log "checking public document deletion across Redis Stack and MinIO"
MINIO_OBJECTS_BEFORE_DELETE="$(minio_object_count)"
[[ "$MINIO_OBJECTS_BEFORE_DELETE" =~ ^[0-9]+$ ]] || fail "MinIO object count was not numeric"
CLEANUP_DOCUMENT="$WORK_DIR/delete-verification.txt"
printf '%s\n' \
  "deletion lifecycle sentinel ${RUN_STAMP}" \
  'This temporary document must disappear from both object storage and hybrid retrieval.' \
  >"$CLEANUP_DOCUMENT"
CLEANUP_UPLOAD_RESPONSE="$(auth_curl -X POST \
  -F 'knowledgeBaseVersion=1' \
  -F 'title=Deletion lifecycle verification' \
  -F "file=@${CLEANUP_DOCUMENT};type=text/plain" \
  "$APP_URL/api/v1/knowledge-bases/$KB_ID/documents")"
CLEANUP_DOCUMENT_ID="$(printf '%s' "$CLEANUP_UPLOAD_RESPONSE" | json_value documentId)"
CLEANUP_JOB_ID="$(printf '%s' "$CLEANUP_UPLOAD_RESPONSE" | json_value jobId)"
[[ -n "$CLEANUP_DOCUMENT_ID" ]] || fail "cleanup upload did not return a document"
[[ -n "$CLEANUP_JOB_ID" ]] || fail "cleanup upload did not return an ingestion job"
wait_for_ingestion_published "$CLEANUP_JOB_ID"
auth_curl -X DELETE "$APP_URL/api/v1/documents/$CLEANUP_DOCUMENT_ID" >/dev/null
MINIO_OBJECTS_AFTER_DELETE="$(minio_object_count)"
[[ "$MINIO_OBJECTS_AFTER_DELETE" == "$MINIO_OBJECTS_BEFORE_DELETE" ]] || \
  fail "document deletion left a MinIO object ($MINIO_OBJECTS_BEFORE_DELETE before, $MINIO_OBJECTS_AFTER_DELETE after)"

log "publishing the verified index and executing hybrid retrieval"
PUBLISH_RESPONSE="$(auth_curl -X POST \
  "$APP_URL/api/v1/knowledge-bases/$KB_ID/versions/1/publish")"
[[ "$(printf '%s' "$PUBLISH_RESPONSE" | json_value status)" == "PUBLISHED" ]] || \
  fail "Knowledge Base version did not publish"
SEARCH_RESPONSE="$(auth_curl -X POST "$APP_URL/api/v1/knowledge-bases/$KB_ID/search" \
  -H 'Content-Type: application/json' \
  -d '{"query":"refund policy","knowledgeBaseVersion":1,"topK":5}')"
printf '%s' "$SEARCH_RESPONSE" | assert_search_result
DELETED_SEARCH_RESPONSE="$(auth_curl -X POST "$APP_URL/api/v1/knowledge-bases/$KB_ID/search" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"deletion lifecycle sentinel ${RUN_STAMP}\",\"knowledgeBaseVersion\":1,\"topK\":5}")"
printf '%s' "$DELETED_SEARCH_RESPONSE" | assert_document_absent "$CLEANUP_DOCUMENT_ID"

log "checking Artifact download authorization"
UNAUTH_ARTIFACT_STATUS="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H "X-Tenant-Id: ${TENANT_ID}" -H "X-Workspace-Id: ${WORKSPACE_ID}" \
  "$APP_URL/api/v1/artifacts/not-present")"
[[ "$UNAUTH_ARTIFACT_STATUS" == "401" ]] || fail "unauthenticated Artifact download was not rejected"
WRONG_WORKSPACE_STATUS="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H "Authorization: Bearer ${TOKEN}" -H "X-Tenant-Id: ${TENANT_ID}" \
  -H 'X-Workspace-Id: inaccessible-workspace' \
  "$APP_URL/api/v1/artifacts/not-present")"
[[ "$WRONG_WORKSPACE_STATUS" == "403" ]] || fail "cross-workspace Artifact download was not rejected"

log "verification passed"
log "Flyway migrations: $FLYWAY_COUNT; runId: $RUN_ID; ingestionJobId: $JOB_ID; knowledgeBaseId: $KB_ID"
log "logs are available in $WORK_DIR"
