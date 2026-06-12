# AI 点评 - 智能本地生活服务平台

Spring Boot + LangChain4j + Redis 构建的本地生活点评系统。项目保留经典点评、优惠券秒杀、关注流、签到等业务能力，并重点升级了店铺 AI 分析模块。

## 核心能力

- 店铺发现：分类浏览、地理位置排序、店铺详情缓存、店铺统计缓存。
- 探店笔记：发布、点赞、热门笔记、关注流、点赞一致性修复。
- 优惠秒杀：Redis Lua 原子校验、Redis Stream 异步落库、一人一单控制。
- 登录鉴权：Sa-Token、RBAC、后台管理、登录审计、风险控制。
- AI 店铺分析：店铺总结、问答、对比、推荐、自然语言入口、证据上下文、记忆隔离、降级治理。

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
- `ShopAIService` 不再是业务 Agent 门面，只保留模型调用方法：通用分析、结构化总结、意图分类、流式输出。
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
- `QA`：店铺问答，先读取同用户同店铺 summary memory，再结合评价证据回答。
- `COMPARE`：店铺对比，按同一 aspect 对两家店铺进行证据对比。
- `RECOMMEND`：店铺推荐，先找候选，再基于偏好和证据生成理由。
- `FREE_CHAT`：能力说明、参数补充引导和低风险自由回答，不访问业务 Tool。

## 证据与记忆治理

- 所有 AI 响应尽量返回 `traceId`、`memoryId`、`evidence`、`degraded`、`cacheHit`。
- 记忆 Key 按功能隔离：summary、QA、compare、recommend、AI chat 互不串号。
- `/ai/chat` 路由到总结时，summary 内容写入 `shop:summary:{shopId}:{userId}`，不会写入通用 chat memory。
- 降级或低置信总结不写入 summary memory，避免后续 QA 使用污染上下文。
- 博客发布、点赞等评价变化会清理店铺 AI 缓存、统计缓存和对应店铺 summary memory。

## RAG 与缓存策略

- L1：Caffeine 本地缓存。
- L2：Redis AI 结果缓存。
- 上下文版本由评价数量、最新评价时间、prompt version、model name 等共同决定。
- RAG 向量库使用 Redis Stack，默认端口 `6380`。
- 生产默认 `rag.redis.fallback-to-memory=false`：`rag.enabled=true` 且 Redis Stack 不可用时启动失败。
- dev/test 可显式设置 `rag.redis.fallback-to-memory=true`，允许回退 `InMemoryEmbeddingStore`。

## 主要接口

| Method | Path | 说明 |
| --- | --- | --- |
| `POST` | `/api/shop-summary/ai/chat` | 自然语言总入口，先路由再进入工作流 |
| `POST` | `/api/shop-summary/ai/chat/stream` | SSE 流式自然语言入口 |
| `GET` | `/api/shop-summary/{shopId}` | 店铺总结 |
| `GET` | `/api/shop-summary/{shopId}/quality` | 高质量评价总结 |
| `POST` | `/api/shop-summary/{shopId}/ask` | 指定店铺问答 |
| `POST` | `/api/shop-summary/compare` | 店铺对比 |
| `POST` | `/api/shop-summary/recommend` | 店铺推荐 |
| `POST` | `/api/shop-summary/{shopId}/with-memory` | 生成总结并写入 summary memory |

响应元信息示例：

```json
{
  "traceId": "9f7c...",
  "memoryId": "hmdp:memory:shop:summary:1:10001",
  "evidence": [],
  "degraded": false,
  "cacheHit": true
}
```

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

# 初始化数据库：执行 src/main/resources/db/hmdp.sql
export DASHSCOPE_API_KEY=your_dashscope_api_key

docker run -d -p 6379:6379 redis:7
docker run -d -p 6380:6379 redis/redis-stack-server:latest

mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

关键配置：

```yaml
rag:
  enabled: true
  redis:
    host: 127.0.0.1
    port: 6380
    index-name: shop_knowledge_base
    dimension: 1024
    fallback-to-memory: false
```

## 测试

```bash
mvn test
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
