# Local Development and MySQL 8 Bootstrap

This guide contains the operational details intentionally kept out of the repository homepage. It covers local infrastructure, the Oracle MySQL 8 Flyway compatibility bridge, backend/frontend startup and verification.

## Prerequisites

- JDK 11 or newer
- Maven 3.8 or newer
- Node.js 20 or newer
- Docker Desktop or Docker Engine with Compose v2
- Python 3.8 or newer for `scripts/verify-ai-platform.sh`
- PowerShell 7 or Windows PowerShell for the MySQL 8 compatibility helper

## Secret handling

Tracked configuration contains placeholders only. Never commit `.env`, database passwords, MinIO credentials or model-provider keys.

`docker compose` reads a repository-root `.env` file automatically. Spring Boot does not; export the corresponding application variables in the shell that starts the backend.

```bash
cp .env.example .env
# Replace every replace_me value with a local-only value.
```

## Start local infrastructure

```bash
docker compose -f docker-compose.ai.yml config
docker compose -f docker-compose.ai.yml up -d --wait
docker compose -f docker-compose.ai.yml ps
```

Default loopback-only ports are:

| Service | Address |
| --- | --- |
| MySQL 8 | `127.0.0.1:3307` |
| Business and memory Redis | `127.0.0.1:6381` |
| Redis Stack | `127.0.0.1:6380` |
| MinIO API | `127.0.0.1:9000` |
| MinIO Console | `127.0.0.1:9001` |

The conventional Redis port `6379` remains free for other local applications.

## Oracle MySQL 8 Flyway compatibility bridge

The repository contains two already-published historical migrations that used MariaDB-only `ADD COLUMN IF NOT EXISTS` syntax. Editing those migrations would cause Flyway checksum mismatches in deployed environments, so Oracle MySQL 8 uses an explicit one-time bridge instead.

Run the bridge after the base `src/main/resources/db/hmdp.sql` import and before the first normal application startup. Back up the database and schedule a maintenance window before using it against an existing environment.

```powershell
.\scripts\repair-mysql8-flyway-compatibility.ps1 `
  -MysqlPath 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' `
  -MysqlHost 127.0.0.1 `
  -MysqlPort 3307 `
  -Database hmdp `
  -Username root `
  -Password '<local-password>' `
  -Confirm:$false
```

The helper:

1. advances normal migrations through `20260720.02`;
2. executes the MySQL 8-compatible SQL in an isolated Flyway history flow;
3. records the original checksums for `20260720.03` (`2143241596`) and `20260721.01` (`814957484`);
4. restores normal Flyway migration handling;
5. only reconciles the two known pre-release success checksums after schema contract validation.

It refuses unknown checksums and never runs a global Flyway repair. History backups are written below `.local-backups/flyway-compat` by default, outside Maven's `target` cleanup. Use `-HistoryBackupDirectory` to select another non-`target` directory.

Do not run this bridge against MariaDB.

## Start the backend

The examples below use the defaults from `docker-compose.ai.yml`. If `.env` changes them, export the same values for Spring Boot.

### Bash

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3307/hmdp?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true'
export DB_USERNAME=root
export DB_PASSWORD=change_me_local
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6381
export MEMORY_REDIS_HOST=127.0.0.1
export MEMORY_REDIS_PORT=6381
export VECTOR_REDIS_HOST=127.0.0.1
export VECTOR_REDIS_PORT=6380
export MINIO_ENDPOINT=http://127.0.0.1:9000
export MINIO_ACCESS_KEY=local_minio_user
export MINIO_SECRET_KEY=change_me_local_minio
export HMDP_SMS_MOCK_ENABLED=true

mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### PowerShell

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3307/hmdp?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = 'change_me_local'
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '6381'
$env:MEMORY_REDIS_HOST = '127.0.0.1'
$env:MEMORY_REDIS_PORT = '6381'
$env:VECTOR_REDIS_HOST = '127.0.0.1'
$env:VECTOR_REDIS_PORT = '6380'
$env:MINIO_ENDPOINT = 'http://127.0.0.1:9000'
$env:MINIO_ACCESS_KEY = 'local_minio_user'
$env:MINIO_SECRET_KEY = 'change_me_local_minio'
$env:HMDP_SMS_MOCK_ENABLED = 'true'

mvn spring-boot:run '-Dspring-boot.run.profiles=local'
```

Verify health at `http://127.0.0.1:8081/actuator/health`.

## Configure a model provider

Agent model calls load a published Model Profile Version. Its `secretRef` must point to an environment reference such as `env:AI_CHAT_API_KEY`; resolved secrets are not stored in model-client cache keys, logs or database summaries.

```bash
export AI_CHAT_BASE_URL='https://provider.example/v1'
export AI_CHAT_API_KEY='<local-secret>'
export AI_CHAT_MODEL='<model-name>'
```

The automated test path uses a local OpenAI-compatible provider and does not require a paid external account.

## Start AI Studio

```bash
cd frontend
npm ci
npm run dev -- --host 127.0.0.1 --port 5173
```

Open `http://127.0.0.1:5173`. When `HMDP_SMS_MOCK_ENABLED=true`, the local login page displays the generated verification code.

## Verification

Backend unit, architecture and contract checks:

```bash
mvn clean verify
```

Container-backed integration profile:

```bash
mvn clean verify -Pfull-integration
```

Full platform acceptance flow:

```bash
./scripts/verify-ai-platform.sh
```

The acceptance script checks infrastructure health, Flyway, application startup, the default Agent seed, Run creation, SSE replay/reconnect/cancellation, knowledge upload, ingestion, hybrid retrieval, document deletion and Artifact authorization.

Frontend checks:

```bash
cd frontend
npm run lint
npm run typecheck
npm run test
npm run build
PLAYWRIGHT_REAL_BACKEND=true npm run test:e2e
```

## Stop infrastructure

Preserve local data:

```bash
docker compose -f docker-compose.ai.yml down
```

Only use `down -v` when the local MySQL, Redis and MinIO volumes are intentionally disposable.

## Troubleshooting

- MySQL connection failure: compare `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` with Compose.
- Flyway failure on Oracle MySQL 8: run the compatibility preflight and bridge above; do not edit published migrations.
- Empty vector results: verify the published Knowledge Base Version and Redis Stack on port `6380`.
- `PROVIDER_NOT_CONFIGURED`: publish a Model Profile Version and export the environment variable referenced by `secretRef`.
- MCP/Dify/HTTP rejection: verify the configured host allowlist and private-network policy.
- Sandbox failure: verify Docker availability and the configured image/command allowlist.
