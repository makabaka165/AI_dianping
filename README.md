# AI Dianping

`AI_dianping` is a Spring Boot 2.7 / Java 11 modular monolith. It preserves the original shop, blog, follow, sign-in and voucher-seckill features while adding a persistent, tenant-aware Agent backend platform.

Implemented platform capabilities include immutable Agent/Prompt/Workflow/Tool/Knowledge versions, persistent Agent runs and node runs, branching/parallel/foreach/loop/pause-resume workflows, Local Skills, MCP, configurable HTTP tools, external search, Dify, Docker sandbox execution, MySQL/MinIO document ingestion, Redis Stack hybrid retrieval, citations, four-layer memory, feedback, deterministic evaluation and management observability.

## Local infrastructure

Requirements: JDK 11+, Maven 3.8+, Docker with Compose v2.

```bash
cp .env.example .env
# Replace local placeholder values in .env. Do not commit .env.
./scripts/start-ai-infra.sh
./scripts/verify-ai-platform.sh
```

PowerShell users can run the equivalent command directly:

```powershell
docker compose -f docker-compose.ai.yml up -d --wait
```

Set the application variables to match Compose, then start the application:

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true'
export DB_USERNAME=root
export DB_PASSWORD=change_me_local
export REDIS_HOST=127.0.0.1 REDIS_PORT=6379 REDIS_PASSWORD=
export MEMORY_REDIS_HOST=127.0.0.1 MEMORY_REDIS_PORT=6379 MEMORY_REDIS_PASSWORD=
export VECTOR_REDIS_HOST=127.0.0.1 VECTOR_REDIS_PORT=6380 VECTOR_REDIS_PASSWORD=
export MINIO_ENDPOINT=http://127.0.0.1:9000
export MINIO_ACCESS_KEY=local_minio_user
export MINIO_SECRET_KEY=change_me_local_minio
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Model calls require published `ai_model_profile` records whose `secret_ref` points to an environment variable such as `env:AI_CHAT_API_KEY`. Default CI tests do not call external models.

数据库初始化：先导入 `src/main/resources/db/hmdp.sql`。启动应用时 Flyway 会自动执行 `src/main/resources/db/migration` 补齐当前结构；不要只导入 `hmdp.sql` 后关闭 Flyway。

## Primary API flow

All `/api/v1/**` calls require `Authorization: Bearer <token>` plus `X-Tenant-Id` and `X-Workspace-Id` headers.

Create an Agent definition (management permission required):

```bash
curl -X POST "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_ID" -H "X-Workspace-Id: $WORKSPACE_ID" \
  -H 'Content-Type: application/json' \
  -d '{"code":"support-agent","name":"Support Agent","description":"Tenant support workflow"}'
```

Create an Agent run:

```bash
curl -X POST "$BASE_URL/api/v1/agent-runs" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "X-Workspace-Id: $WORKSPACE_ID" \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"shop-consultant","agentVersion":1,"sessionId":"session-123","input":{"text":"对比 1 号店和 2 号店的服务态度","parts":[],"attachments":[],"referenceUris":[]},"responseMode":"STREAM","metadata":{"channel":"web"}}'
```

Watch SSE events:

```bash
curl -N "$BASE_URL/api/v1/agent-runs/$RUN_ID/events" \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: $TENANT_ID" -H "X-Workspace-Id: $WORKSPACE_ID"
```

Create a knowledge base and upload a document:

```bash
curl -X POST "$BASE_URL/api/v1/knowledge-bases" -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID" -H "X-Workspace-Id: $WORKSPACE_ID" \
  -H 'Content-Type: application/json' -d '{"code":"shop-enterprise-knowledge","name":"Shop enterprise knowledge","description":"Policies and operating knowledge"}'

curl -X POST "$BASE_URL/api/v1/knowledge-bases/$KB_ID/documents" -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID" -H "X-Workspace-Id: $WORKSPACE_ID" \
  -F 'file=@docs/example.pdf'
```

See executable request templates under `docs/examples/` and the contract in `docs/api/openapi.yaml`.

## Security defaults

- No real database password, Redis password, model key or MinIO credential is stored in tracked configuration.
- Production disables LangChain4j request/response bodies, HTTP wire logs, full prompts and full retrieved chunks.
- Knowledge ACL filters are applied before vector and lexical retrieval.
- HTTP/MCP/search/reference calls reject loopback, link-local and private addresses unless an administrator explicitly enables a trusted internal endpoint.
- High/critical tools require approval. Sandbox networking is disabled and commands are allow-listed.
- Artifact downloads are tenant/workspace scoped and restricted to the creating user or an administrator.
- MCP registration requires both `MCP_MANAGE` and `ADMIN`.

`AITestController` and load-test endpoints exist only in `local`, `dev` and `test` profiles.

## Compatibility

Legacy shop AI endpoints remain available and delegate to the new Agent runtime. They return `Deprecation: true`; new clients should use `POST /api/v1/agent-runs`. No frontend directory is modified by this backend migration.

## Verification

```bash
mvn clean test
mvn clean verify
```

Container-backed tests are tagged `integration` and need Docker. Live model tests use the separate `ai-live-test`/`external` path and are excluded from default CI.

Architecture, operations, migration and security details are in [docs/architecture/agent-platform-overview.md](docs/architecture/agent-platform-overview.md), [docs/migration/legacy-ai-api.md](docs/migration/legacy-ai-api.md) and [docs/security/secret-remediation.md](docs/security/secret-remediation.md).
