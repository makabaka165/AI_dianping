# AI 点评 - 智能本地生活服务平台

Spring Boot + LangChain4j + Redis 构建的本地生活点评系统。项目保留经典点评、优惠券秒杀、关注流、签到等业务能力，并重点升级了店铺 AI 分析模块。

## 核心能力

- 店铺发现：分类浏览、地理位置排序、店铺详情缓存、店铺统计缓存。
- 探店笔记：发布、点赞、热门笔记、关注流、点赞一致性修复。
- 优惠秒杀：Redis Lua 原子校验、Redis Stream 异步落库、一人一单控制。
- 登录鉴权：Sa-Token、RBAC、后台管理、登录审计、风险控制。
- AI 店铺分析：店铺总结、问答、对比、推荐、自然语言入口、Typed Evidence、结构化响应、记忆隔离、降级治理。
- AI 治理观测：Micrometer/Prometheus 指标、模型 token 估算、prompt 版本灰度、评价 RAG 混合召回。

## 当前 AI 架构

AI 模块已经从旧的“单 Agent 自动 Tool Calling”升级为显式编排和工作流模式：

```text
ShopSummaryController
  -> ShopAIApplicationService
      -> ShopAIOrchestrator
          -> ChatWorkflow
          -> SummaryWorkflow / QualitySummaryWorkflow
          -> QAWorkflow
          -> CompareWorkflow
          -> RecommendWorkflow
          -> ModelGateway
          -> MemoryService
          -> QualityGuard
          -> FallbackPolicy
```

关键边界：

- `ShopAIApplicationService` 是 Controller 的 AI 统一入口，负责 traceId、sessionId、ThreadLocal 请求上下文。
- `ShopAIOrchestrator` 只做任务分发，核心业务由显式 Workflow 承担。
- `ModelGateway` 是唯一模型适配层，包装底层 LangChain4j `ShopAIService`。
- `ShopAIService` 不再是业务 Agent 门面，只保留工作流需要的底层模型调用能力；业务侧统一通过 `ModelGateway` 获得结构化结果。
- `ShopFreeChatAIService` 不暴露 `ShopTool`，普通自由对话不再自动 Function Calling。
- `ShopTool` 暂留为确定性工具，不参与核心总结、问答、对比、推荐链路。

## AI 工作流

自然语言入口 `/api/shop-summary/ai/chat` 会先进行意图路由，再进入确定工作流：

```text
用户问题
  -> IntentRouteCoordinator
      -> RuleIntentParser
      -> LLMIntentClassifier
      -> IntentSlotMemoryService
  -> Workflow
      -> ContextAssembler / EvidenceRetriever
      -> PromptTemplateRegistry
      -> ModelGateway
      -> QualityGuard
      -> FallbackPolicy
  -> Result
```

支持的意图：

- `SUMMARY`：店铺总结，结构化 JSON 一次模型调用生成 summary/sentiment/keywords/pros/cons/confidence。
- `QA`：店铺问答，先读取同用户同店铺 summary memory，再结合评价证据生成 `qa` payload。
- `COMPARE`：店铺对比，按同一 aspect 对两家店铺进行证据对比，生成 `compare` payload。
- `RECOMMEND`：店铺推荐，先找候选，再基于偏好和证据生成 `recommend` payload。
- `FREE_CHAT`：能力说明、参数补充引导和低风险自由回答，不访问业务 Tool。

## 证据、响应与记忆治理

- 同步 AI 接口统一返回 `ShopAIResponse` 元信息和结构化 payload，不再保留旧的顶层 `response/answer/comparison/recommendations/usedTools` 字段。
- 当前 payload 只会填充一种：`summary`、`qa`、`compare`、`recommend`、`chat`。
- `evidence` 使用 Typed Evidence：评价证据为 `REVIEW`，ID 形如 `review:{blogId}`；店铺资料证据为 `SHOP_PROFILE`，ID 形如 `shop_profile:{shopId}`。
- 模型输出中的 `evidenceIds` 必须引用本次返回的 `EvidenceItem.id`，质量校验会拒绝不存在的证据引用。
- 记忆 Key 按功能隔离：summary、QA、compare、recommend、AI chat 互不串号。
- `/ai/chat` 路由到总结时，summary 内容写入 `shop:summary:{shopId}:{userId}`，不会写入通用 chat memory。
- 降级或低置信总结不写入 summary memory，避免后续 QA 使用污染上下文。
- 博客发布、点赞等评价变化会清理店铺 AI 缓存、统计缓存和对应店铺 summary memory。

## RAG 与缓存策略

- L1：Caffeine 本地缓存。
- L2：Redis AI 结果缓存。
- 上下文版本由评价数量、最新评价时间、prompt version、model name 等共同决定。
- RAG 向量库使用 Redis Stack，默认端口 `6380`。
- 店铺评价证据采用规则 + 向量混合召回：先取高赞、近期、负面候选，再叠加评价向量搜索结果，最后用确定性 rerank 统一排序。
- 评价向量索引由博客发布事件自动追加；点赞事件只清缓存，不重新 embedding。由于当前 LangChain4j 版本没有删除接口，旧向量通过 DB 状态和 contentHash 在检索时过滤。
- 生产默认 `rag.redis.fallback-to-memory=false`：`rag.enabled=true` 且 Redis Stack 不可用时启动失败。
- dev/test 可显式设置 `rag.redis.fallback-to-memory=true`，允许回退 `InMemoryEmbeddingStore`。
- 平台 FAQ/政策知识库与店铺评价 RAG 使用独立索引：`platform_policy_kb` 与 `shop_review_kb`。
- 启动期不再调用外部 embedding API 做探测；平台知识库自动导入失败只告警，不阻塞应用启动。

## AI 观测与 Prompt 灰度

- 默认只暴露 `health`、`info`；需要 Prometheus 时设置 `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus`。
- AI 指标包括请求耗时、模型耗时、估算 token、缓存命中、降级次数、质量拒绝、证据数量、RAG 搜索和索引统计。
- AI 入口增加用户级配额，默认每用户每分钟 10 次、每天 200 次；系统级 `ModelGateway` RateLimiter 仍作为全局保护。
- `ModelGateway` 会按模型调用记录估算输入/输出 token；该估算用于成本趋势，不等同于供应商精确计费。
- Prompt 默认全部走 stable 版本；开启 canary 后按 `userId + intent + routeKey` 稳定 hash，保证同一用户同任务命中稳定版本。

## 第三批可靠性收尾

本轮收尾聚焦可靠性、性能和运维可观测性，不改变接口 URL、`Result` 外壳、权限体系，也不推进 AI 微服务拆分或引入新的重量级中间件。项目最终定位为 AI 增强型本地生活平台：在单体内完成 AI 子系统模块化抽取，通过 Port/Adapter 与 ArchUnit 约束为后续按需服务化预留边界，并结合高并发秒杀、缓存治理、安全收束、数据一致性和可靠性收尾，形成当前阶段的完整工程形态。

- 秒杀订单消费线程和超时关闭线程可配置，Redis Stream ready 门控默认开启；Stream/consumer group 未就绪时默认快速拒绝秒杀请求，避免 Stream 异常时继续执行 Lua 静默接单。
- AI task 增加 RUNNING heartbeat、stuck 扫描、超时重入队、最大重试次数和失败收敛，仍保留当前 Redis BlockingQueue 架构；未升级为 Redis Stream 是本轮有意控制范围。
- 附近店铺 GEO 查询在存在业务过滤条件时按配置循环扩大候选窗口，`max-scan-size` 防止无限扫描，减少过滤后分页不足和 `hasMore` 误判。
- CacheClient 互斥锁超时后先短暂 retry 读取缓存，默认不直接 fallback DB；店铺详情会把热点保护状态返回为 `SYSTEM_BUSY`，保护数据库。

## 主要接口

| Method | Path | 说明 |
| --- | --- | --- |
| `POST` | `/api/shop-summary/ai/chat` | 自然语言总入口，先路由再进入工作流 |
| `POST` | `/api/shop-summary/ai/chat/stream` | SSE 流式自然语言入口 |
| `GET` | `/api/shop-summary/{shopId}` | 店铺总结 |
| `GET` | `/api/shop-summary/{shopId}/quality` | 高质量评价总结，不写记忆 |
| `POST` | `/api/shop-summary/{shopId}/quality/with-memory` | 高质量评价总结并写入 summary memory |
| `POST` | `/api/shop-summary/{shopId}/ask` | 指定店铺问答 |
| `POST` | `/api/shop-summary/compare` | 店铺对比 |
| `POST` | `/api/shop-summary/recommend` | 店铺推荐 |
| `POST` | `/api/shop-summary/{shopId}/with-memory` | 生成总结并写入 summary memory |
| `POST` | `/api/shop-summary/admin/rag/shops/{shopId}/rebuild` | 管理端回补单店评价 RAG 索引 |
| `POST` | `/api/shop-summary/admin/rag/rebuild` | 管理端回补全部评价 RAG 索引 |
| `GET` | `/actuator/prometheus` | Prometheus 指标（需显式启用） |

结构化响应示例：

```json
{
  "sessionId": "default",
  "traceId": "9f7c...",
  "intent": "QA",
  "evidence": [
    {
      "id": "review:88",
      "type": "REVIEW",
      "shopId": 1,
      "sourceId": 88,
      "snippet": "服务响应比较快，排队时会主动提醒。",
      "matchedReason": "关键词匹配: 服务",
      "score": 0.83
    }
  ],
  "confidence": 0.78,
  "degraded": false,
  "cacheHit": false,
  "qa": {
    "shopId": 1,
    "question": "这家店服务怎么样？",
    "answer": "从现有评价看，服务响应较积极，但样本仍有限。",
    "evidenceIds": ["review:88"],
    "insufficientEvidence": false
  }
}
```

SSE 流式接口继续保留 `delta.text` 事件；其中的 `evidence` 事件同样使用 `EvidenceItem` 结构。

## 环境要求

| 组件 | 用途 |
| --- | --- |
| JDK 11+ | 运行环境 |
| MySQL 5.7+ | 业务数据 |
| Redis 6/7 | 缓存、分布式锁、Stream、聊天记忆，默认 `6379` |
| Redis Stack 7+ | RAG 向量存储，默认 `6380` |
| Maven 3.6+ | 构建 |

## 启动

```bash
git clone https://github.com/makabaka165/AI_dianping.git
cd AI_dianping

# 初始化数据库：先导入 `src/main/resources/db/hmdp.sql` 作为 demo baseline 数据。
# 启动应用时 Flyway 会自动执行 `src/main/resources/db/migration`，补齐当前 schema。
# 不要只导入 `hmdp.sql` 后关闭 Flyway，否则可能缺少当前代码依赖的字段、索引或权限表。
export DASHSCOPE_API_KEY=your_dashscope_api_key

docker run -d -p 6379:6379 redis:7
docker run -d -p 6380:6379 redis/redis-stack-server:latest

mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

本地可从 `src/main/resources/application-example.yaml` 复制配置形态。不要把真实数据库密码、Redis 密码或模型 Key 提交到仓库；优先使用环境变量覆盖。

常用环境变量：

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `MYSQL_URL` | MySQL JDBC 地址 | `jdbc:mysql://127.0.0.1:3306/hmdp?...` |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | MySQL 用户名和密码 | `root` / 空 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 业务 Redis，缓存、Stream、记忆、配额使用 | `127.0.0.1:6379` |
| `RAG_REDIS_HOST` / `RAG_REDIS_PORT` | Redis Stack，RAG 向量检索使用 | `127.0.0.1:6380` |
| `API_KEY` 或 `DASHSCOPE_API_KEY` | OpenAI compatible / DashScope 模型 Key | 空 |
| `AI_LOG_REQUESTS` / `AI_LOG_RESPONSES` | 模型请求/响应详细日志 | `false` |
| `CHAT_MEMORY_TTL_*` | 不同 AI 记忆类型的 TTL 秒数 | 见 `application.yaml` |

关键配置：

```yaml
rag:
  enabled: true
  review:
    enabled: true
    index-name: shop_review_kb
    min-score: 0.55
    max-vector-candidates: 20
    backfill-page-size: 200
  platform-policy:
    index-name: platform_policy_kb
  redis:
    host: 127.0.0.1
    port: 6380
    dimension: 1024
    fallback-to-memory: false

hmdp:
  redis:
    redisson:
      host: ${HMDP_REDIS_HOST:${spring.redis.host:127.0.0.1}}
      port: ${HMDP_REDIS_PORT:${spring.redis.port:6379}}
      password: ${HMDP_REDIS_PASSWORD:${spring.redis.password:}}
      database: ${HMDP_REDIS_DATABASE:${spring.redis.database:0}}
      ssl: ${HMDP_REDIS_SSL:false}
      timeout-millis: ${HMDP_REDIS_TIMEOUT_MILLIS:3000}
      connect-timeout-millis: ${HMDP_REDIS_CONNECT_TIMEOUT_MILLIS:3000}
      retry-attempts: ${HMDP_REDIS_RETRY_ATTEMPTS:3}
      retry-interval-millis: ${HMDP_REDIS_RETRY_INTERVAL_MILLIS:1500}
  cache:
    mutex:
      wait-timeout-millis: 300
      lease-time-seconds: 10
      retry-after-fail:
        enabled: ${HMDP_CACHE_MUTEX_RETRY_AFTER_FAIL_ENABLED:true}
        max-attempts: ${HMDP_CACHE_MUTEX_RETRY_AFTER_FAIL_MAX_ATTEMPTS:3}
        sleep-millis: ${HMDP_CACHE_MUTEX_RETRY_AFTER_FAIL_SLEEP_MILLIS:50}
        fallback-to-db: ${HMDP_CACHE_MUTEX_RETRY_AFTER_FAIL_FALLBACK_TO_DB:false}
  voucher:
    order:
      worker-threads: ${HMDP_VOUCHER_ORDER_WORKER_THREADS:2}
      close-worker-threads: ${HMDP_VOUCHER_ORDER_CLOSE_WORKER_THREADS:1}
      stream-required: ${HMDP_VOUCHER_ORDER_STREAM_REQUIRED:true}
      stream-health-check-enabled: ${HMDP_VOUCHER_ORDER_STREAM_HEALTH_CHECK_ENABLED:true}
  shop:
    nearby:
      initial-fetch-multiplier: ${HMDP_SHOP_NEARBY_INITIAL_FETCH_MULTIPLIER:3}
      fetch-growth-factor: ${HMDP_SHOP_NEARBY_FETCH_GROWTH_FACTOR:2}
      max-scan-size: ${HMDP_SHOP_NEARBY_MAX_SCAN_SIZE:200}
  ai:
    task:
      heartbeat-interval-seconds: ${HMDP_AI_TASK_HEARTBEAT_INTERVAL_SECONDS:30}
      running-timeout-minutes: ${HMDP_AI_TASK_RUNNING_TIMEOUT_MINUTES:30}
      max-retry-count: ${HMDP_AI_TASK_MAX_RETRY_COUNT:3}
      stuck-scan-enabled: ${HMDP_AI_TASK_STUCK_SCAN_ENABLED:true}
      stuck-scan-fixed-delay-millis: ${HMDP_AI_TASK_STUCK_SCAN_FIXED_DELAY_MILLIS:60000}
      stuck-scan-limit: ${HMDP_AI_TASK_STUCK_SCAN_LIMIT:100}
    quota:
      enabled: true
      minute-permits: 10
      daily-permits: 200
      fail-open: false
    prompt:
      canary:
        enabled: false
        ratio: 0
  security:
    forwarded-headers:
      enabled: false
      trusted-proxies: 127.0.0.1,::1
    device-fingerprint:
      trust-client-header: false
  sms:
    mock:
      enabled: false
  upload:
    blog-image:
      owner-ttl-days: 7

sa-token:
  timeout: 604800
  active-timeout: 7200
  is-share: false
```

Database initialization: `hmdp.sql` is retained as the demo baseline with sample data, while Flyway migrations are the current schema alignment path. For a new local/demo environment, first import `src/main/resources/db/hmdp.sql`, then start the application or run Flyway migrate so `src/main/resources/db/migration` can finish schema alignment. `spring.flyway.baseline-on-migrate=true` is kept so an existing non-empty schema created from `hmdp.sql` can be brought under Flyway management on first application startup. Do not disable Flyway after importing `hmdp.sql`.

Flyway production guidance: local/demo environments may let the application run Flyway migrations automatically. For real production, prefer running Flyway migrate in CI/CD before releasing the application instead of relying on application startup to change the database. Once a migration has run in production, do not edit it casually; add a new migration for follow-up schema fixes. If a checksum mismatch appears because an old migration was made idempotent, local/dev may use `flyway repair`; staging should back up and confirm semantic equivalence before repair; production should not repair blindly, and should usually restore the original migration plus add a new patch migration.

Redis configuration: business cache, Spring Redis, Redisson, distributed locks, Redis Stream, AI memory, quota, and delayed tasks use the same business Redis on `6379` by default. Production should explicitly set `HMDP_REDIS_HOST`, `HMDP_REDIS_PORT`, `HMDP_REDIS_PASSWORD`, `HMDP_REDIS_DATABASE`, and `HMDP_REDIS_SSL`, keep Spring Redis and Redisson pointed at that same business Redis, keep `hmdp.ai.redisson-fallback=false`, and never log the Redis password.

RAG Redis configuration: vector retrieval uses a separate Redis Stack instance through `rag.redis.host` and `rag.redis.port`, with default port `6380`. Keep RAG Redis Stack separate from the business Redis/Redisson configuration. Keep `rag.review.dimension` aligned with the embedding model dimension; the current value is `1024`. Production should keep `rag.redis.fallback-to-memory=false` to avoid inconsistent in-memory vector indexes across instances.

Platform policy RAG rebuild/compact note: platform FAQ/policy uses the `platform_policy_kb` Redis Stack index and is refreshed by document import or startup auto-import when `rag.data.auto-import=true`. The current LangChain4j `RedisEmbeddingStore` cannot precisely delete old vectors by `documentId`, so physical compaction requires an out-of-band Redis Stack index rebuild, such as deleting/recreating only `platform_policy_kb` during a maintenance window and then re-running the platform policy import. Deleted or superseded documents remain protected at query time by MySQL document status and contentHash filtering, so stale chunks must not be returned by retrieval even when old vectors are still physically present.

Security defaults: forwarded IP and device fingerprint headers are not trusted unless explicitly enabled; mock SMS code exposure is disabled by default. Keep `HMDP_SMS_MOCK_ENABLED=false`, `hmdp.security.device-fingerprint.trust-client-header=false`, and `SA_TOKEN_IS_SHARE=false` in production. Enable forwarded headers only behind real trusted reverse proxies, and set `trusted-proxies` to explicit proxy egress IPs, never `*`, broad CIDRs, or user networks. The `prod`/`production` profile fails startup when mock SMS is enabled, client device fingerprint headers are trusted, Sa-Token token sharing is enabled, or forwarded headers are enabled with empty/wildcard trusted proxies. Defaults are `SA_TOKEN_TIMEOUT=604800`, `SA_TOKEN_ACTIVE_TIMEOUT=7200`, `SA_TOKEN_IS_CONCURRENT=true`, and blog image owner TTL 7 days. For admin or merchant accounts, use shorter session timeouts where possible.

## 当前能力边界

- 当前仍是单体应用，AI 子系统通过应用服务、编排器、工作流和 Port/Adapter 做内部边界约束，没有拆成微服务。
- 本地限流使用 Caffeine/本机内存，适合当前单实例或小规模部署；多实例强一致限流后续可再演进。
- 业务 Redis 默认 `6379`，承载缓存、锁、Stream、聊天记忆、配额和任务队列；RAG 向量检索使用独立 Redis Stack，默认 `6380`。
- RAG 文档元数据和正文已持久化到 MySQL。删除文档时会标记 MySQL 状态为 `DELETED`；当前 LangChain4j `RedisEmbeddingStore` 不方便按 `documentId` 物理删除旧向量，因此需要依赖元数据状态/检索过滤继续治理。
- AI 请求/响应详细日志默认关闭。调试时再显式设置 `AI_LOG_REQUESTS=true` 或 `AI_LOG_RESPONSES=true`，并避免在生产输出完整用户问题、prompt 或模型响应。

## 常见问题

- `API_KEY` 未配置或已欠费：不要运行会真实调用模型的测试；只跑 mock/unit 测试或 `mvn -DskipTests package`。应用启动可保留空 Key，但访问真实 AI 接口会失败。
- Redis Stack 未启动：`rag.enabled=true` 且 `rag.redis.fallback-to-memory=false` 时，向量库不可用会导致 RAG 相关 Bean 启动失败；本地临时调试可设置 `RAG_ENABLED=false` 或 `RAG_REDIS_FALLBACK_TO_MEMORY=true`。
- MySQL 密码不同：设置 `MYSQL_PASSWORD`，不要把密码写死到 `application.yaml`。
- 需要查看模型请求细节：只在本地短时间开启 `AI_LOG_REQUESTS`/`AI_LOG_RESPONSES`，排查结束后关闭。

## 测试

默认 `pom.xml` 已排除 `external` 和 `integration` 分组，其中 `AIServiceTest` 属于真实模型外部测试。如果当前 `API_KEY` 失效或不希望产生费用，不要单独运行该测试，也不要改 Maven 分组排除规则。

```bash
mvn test
mvn "-Dtest=ChatMemoryKeyManagerTest,RedissonChatMemoryStoreTest,LocalCacheManagerRateLimitTest,VoucherOrderServiceImplTest,DocumentManagementServiceImplTest" test
mvn "-Dtest=AiMetricsServiceTest,AiTokenEstimatorTest,PromptVersionPolicyTest,ModelGatewayTest,ShopReviewVectorIndexServiceTest,ShopReviewEvidenceRetrieverTest,ShopAICacheInvalidationEventListenerTest,ShopAIRagAdminControllerTest,ShopSummaryControllerArchitectureTest" test
mvn "-Dtest=PromptTemplateRegistryTest,ShopReviewEvidenceRetrieverTest,ShopContextAssemblerTest,QualityGuardTest,ModelGatewayTest,QAWorkflowTest,CompareWorkflowTest,RecommendWorkflowTest,ChatWorkflowStreamTest,ShopSummaryControllerArchitectureTest" test
mvn "-Dtest=FallbackPolicyTest,ModelGatewayTest,ShopToolContextTest,ShopSummaryControllerArchitectureTest" test
mvn "-Dtest=ChatWorkflowStreamTest,SummaryWorkflowTest,QualitySummaryWorkflowTest,ShopAICacheInvalidationServiceTest,ShopAIApplicationServiceTest" test
```

## 目录提示

```text
src/main/java/com/hmdp/
  ai/
    application/      AI 应用用例入口、缓存失效、记忆管理入口
    orchestration/    Orchestrator 与请求上下文
    workflow/         Summary/QA/Compare/Recommend/Chat 显式工作流
    intent/           规则 + LLM 意图路由
    model/            ModelGateway
    memory/           MemoryService
    prompt/           PromptTemplateRegistry
    guard/            QualityGuard
    fallback/         FallbackPolicy
  service/ai/         LangChain4j 模型适配接口
  tools/              暂留的确定性 Tool，不参与核心工作流
```
