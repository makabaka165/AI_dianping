# 下一步骤执行提示词

> 状态（2026-07-24）：收束复验已通过（见 `ai-project-audit-2026-07-23.md` 第 7 节），后端阶段已冻结提交，项目进入前端阶段。本提示词继续适用于生产桥接与阶段 C～G 的后端跟进工作，不阻塞前端开发。

以下内容可以直接交给下一轮 Codex、开发者或 CI 修复代理执行。执行者必须在当前仓库继续工作，不要重置已有未提交修改。

## 角色与目标

你是本项目的高级 Java/Spring AI 工程师。`AI_dianping` 的本地发布候选门禁、Compose MySQL 历史修复和完整平台 E2E 已通过；目标是保持这些门禁可重复，并继续处理生产桥接、外部资源清理重试、Redis v1/v2 运维迁移、异步任务和流式结构化质量缺口。所有结论必须有代码、测试或运行日志证据。

## 已知基线

- Java 11、Spring Boot 2.7.18、LangChain4j 0.30.0。
- 默认主机 MySQL 端口为 `3307`，Redis Stack 为 `6380`，Compose 业务 Redis 为 `6381`；所有 Compose 基础设施端口仅绑定 `127.0.0.1`，既有独立 Redis Stack 保留在 `127.0.0.1:6379`。
- 历史 Flyway 文件必须保持不可变；当前修复应使用新的 `VYYYYMMDD_nn__...sql` 前向迁移。
- `RedisStackKnowledgeIndexAdapter` 已有 `FT.INFO` schema 校验、scope/维度/ACL 校验和 RESP2/RESP3 解析保护。
- `RedisKnowledgeIndexNaming` 已统一 JDBC 元数据与 Redis 的物理名称；新写入使用 `ai_kb_v2~` + UTF-8 SHA-256 前 32 位，查询保留 v1 回退；`V20260723_02` 校正可确认的旧元数据，`V20260723_04` 不会盲改 READY/active 物理名。
- Oracle MySQL 8 的历史 `V20260720.03`/`V20260721.01` 含 MariaDB-only 条件列语法；必须先执行 `scripts/repair-mysql8-flyway-compatibility.ps1`，该桥接不是应用启动时自动执行。原始历史文件和 checksum 不得修改；脚本只允许在精确校验和备份后处理缺失/失败记录或两条已知成功旧 checksum，禁止全局 `Flyway repair`。
- PowerShell 失败记录分支已在一次性 MySQL 8.0.37 容器通过；成功旧 checksum 分支已在隔离 MySQL 8.0.36 验证“已知值成功、未知值拒绝且不半更新”。当前 Compose MySQL 8.0.37 已完成修正、迁移和幂等复跑，最终 37 条成功历史、0 失败、最新 `V20260723.05`。迁移前备份因旧默认路径位于 `target` 而被后续 `mvn clean` 删除；脚本现改用 `.local-backups/flyway-compat` 并拒绝 `target`，生产绝不能复用已丢失备份作为回滚证据。
- `DefaultAgentRuntime` 已在 `runs.complete(...)` 前调用完成观察器。
- 非流式 `QAWorkflow` 与流式结构化 QA 都会把 expected shopId 传入 `QualityGuard.validateQA`；流式生产类型是 `ask`，兼容旧 `qa`，模型返回其他店铺 shopId 时必须 repair 或 fallback。
- `QualityGuard.validateRecommend` 已校验 `message/reason/suitableFor/uncertainty`，并拒绝重复 shopId、跨店铺 evidenceIds 和空请求上下文；解析器以请求元数据和候选店铺字段为权威值。
- 任务 JSON 与状态索引批量写入使用 Redisson `REDIS_WRITE_ATOMIC`；inflight 清理使用 taskId compare-delete，`Error` 路径保留标记并由恢复扫描接管。
- 公开文档删除会按 tenant/workspace 收集全部版本对象，提交后删除 Redis Stack chunk 和 MinIO 对象；本地 E2E 已验证对象计数恢复和已删 documentId 不可检索，但提交后外部删除失败尚无持久重试消费者。
- 完成观察器失败会阻止 Run 标记完成；SSE executor 在 `@PreDestroy` 释放；Redis 缺少 RediSearch 模块或 ACL principal 非法会显式失败。
- 流式结构化输出已做有界缓冲、JSON 分类和最终质量审计；结构化模型异常会返回 `MODEL_UNAVAILABLE` 类型化 fallback，但仍是完整收集后再释放最终结果，不是真正逐 token 增量输出。
- Docker daemon 当前可用；Surefire 基线为 `186 suites / 692 tests / 0 failure / 0 error / 0 skipped`，Failsafe 为 `19 suites / 22 tests / 0 failure / 0 error / 0 skipped`。
- `ApplicationContextSmokeIT` 校验每个旧 AI Service 接口恰好一个代理；`ExecutableJarSmokeIT` 会在隔离 MySQL/Redis 上启动重打包 JAR 并探测 HTTP 端点。
- Redis 端口冲突已关闭：Compose 业务 Redis 默认使用 6381，向量 Redis Stack 使用 6380，既有 `hmdp-redis-stack:6379` 保持不变；全部 Compose 端口仅绑定回环地址，验证脚本会拒绝连接到非本次 Compose 的 DB/Redis/MinIO 端点。当前 Compose MySQL 两条 checksum 已符合仓库合同，最新迁移为 `V20260723.05`。
- 主机 Git Bash 位于 `D:\Git\bin\bash.exe`；完整 `verify-ai-platform.sh` 已使用本地 OpenAI-compatible stub 通过，最新有效日志目录为 `target/verify-ai-platform-1784860122-404`。这不代表真实模型供应商的鉴权、限流、超时和响应兼容性已经验证。

## 不可违反的约束

1. 先运行 `git status --short`，保留用户已有修改；禁止 `git reset --hard`、`git checkout --` 或批量删除。
2. 手工编辑使用 `apply_patch`；不要用 Python 或 shell 重定向覆盖代码文件。
3. 不得修改任何已执行的历史 Flyway migration；需要修复时新增迁移，并说明数据前置条件、回滚和 checksum 影响。
4. 不得绕过租户、workspace、knowledge base、ACL 或 Agent 权限边界；新增测试必须覆盖拒绝路径。
5. 不得把 Docker/Testcontainers 跳过当成通过；报告中分别记录 `passed`、`failed`、`skipped`。
6. 不要为了让测试通过而放宽生产校验、吞掉 Redis/MySQL 异常或删除集成测试。
7. 变更完成后运行格式检查，并只提交与本任务有关的文件。

## 阶段 A：环境与基线

执行：

```text
git status --short
mvn -q clean test
mvn -q spotless:check
mvn -q -DskipTests package
docker info
powershell -NoProfile -Command "`$tokens=`$null; `$errors=`$null; [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path 'scripts/repair-mysql8-flyway-compatibility.ps1'), [ref]`$tokens, [ref]`$errors) | Out-Null; if (`$errors.Count -gt 0) { exit 1 }"
```

记录：从 `target/surefire-reports` 和 `target/failsafe-reports` XML 提取 suite/测试数、失败数、跳过数、每个跳过原因、Docker daemon 状态和 JAR 路径。已知通过基线是 Surefire `186/692`、Failsafe `19/22` 且 `skipped=0`；任何回退都必须逐项解释。

## 阶段 B：容器集成闭环

Oracle MySQL 8 在启动应用前先执行一次显式桥接（完整数据库备份、维护窗口和 `-Confirm:$false` 由运维人员确认）：

```text
powershell -ExecutionPolicy Bypass -File .\scripts\repair-mysql8-flyway-compatibility.ps1 -MysqlPath "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -MysqlHost 127.0.0.1 -MysqlPort 3307 -Database hmdp -Username root -Password "<password>" -Confirm:$false
```

验收必须包括：全新 `hmdp.sql` 库、已有 `V20260720.03` 失败记录库、两条已知成功旧 checksum 库、未知 checksum 拒绝路径和幂等复跑；核对发布 checksum (`2143241596`、`814957484`) 及最终 `V20260723.05`。隔离与当前 Compose 证据均已通过，但生产仍必须独立备份和审计；MariaDB 不执行该桥接。

启动：

```text
docker compose -f docker-compose.ai.yml up -d --wait
mvn -q -Pfull-integration verify
```

本机 Redis 端口已经隔离：Compose 业务 Redis 使用 6381，向量 Redis Stack 使用 6380，既有 6379 服务及其数据保持不变。Compose MySQL 的旧 checksum 来源和 schema 已审计并使用精确 reconciliation 修正；迁移前备份因旧 `target` 路径已丢失，当前状态备份保存在 `.local-backups/flyway-compat`，不能把它当作迁移前回滚点。不得将 reconciliation 放宽到其他 checksum 或改成全局 repair。

必须核对：

- MySQL 从 `src/main/resources/db/hmdp.sql` 初始化后，Flyway 全量迁移成功。
- Redis Stack 能创建索引，`FT.INFO` 返回的前缀、字段类型、TAG separator、全文权重和向量参数与代码一致。
- RESP2/RESP3 下向量和全文查询均返回正确 chunkId/score。
- 不同 tenant/workspace/user 不能互相检索；额外 ACL 或错误 token 必须失败。
- MinIO 对象上传/读取/删除及公开 API 的解析、摄取、发布、混合检索和文档删除/索引清理完整链路已自动化通过；下一步补提交后外部清理失败的持久重试。
- Failsafe 报告中容器测试 `skipped=0`。

当前已知基线：Surefire `186 suites / 692 tests / 0 failure / 0 error / 0 skipped`；Failsafe `19 suites / 22 tests / 0 failure / 0 error / 0 skipped`。容器证据包括 MySQL 8 迁移、Redis/Redis Stack、MinIO 对象往返、Spring 全上下文、可执行 JAR 和 checksum reconciliation；Git Bash E2E 另行覆盖公开 API 知识生命周期，但使用的是本地模型 stub。

如果 `FT.INFO` 实际字段结构与适配器假设不同，优先增加兼容解析和契约测试；不要直接删除 schema 校验。

## 阶段 C：索引命名与迁移安全

验证已实现的 `RedisKnowledgeIndexNaming.indexName(...)` hash v2 与 `legacyIndexName(...)` 回退，例如 `a-b` 与 `a_b`。在 Redis Stack 中完成 v1/v2 双读、切换、回滚和孤儿清理演练，不能直接覆盖现有 v1 映射，并且必须同时提供：

- 新旧索引映射表和迁移脚本/运维命令；
- 双读或切换策略；
- 回滚和孤儿索引清理策略；
- 对已存在错误 schema 的明确失败信息；
- 单测和 Redis Stack 集成测试。

在生产候选库执行 `docs/review/sql/ai-schema-migration-preflight.sql`：检查重复 `deduplication_key`、dead-letter 组合键、全局 index-version code 和投影物理名碰撞；不得静默删除业务记录。隔离 MySQL 8.0.46 已证明 `V20260723_01` 至 `V20260723_05` 可执行，下一步要审计生产数据中的自定义名称，并验证 READY/active 行保持现有物理名、只有 `BUILDING/active=0` 行切换到 v2。

## 阶段 D：RAG 完整化

按小步提交实现，保持 `KnowledgeIndexPort` 和租户边界不变：

1. 文档清洗：编码归一化、HTML/Markdown 噪声、重复段、敏感信息和失败原因可观测。
2. 细粒度切片：标题/段落/句子/表格/PDF 结构，记录 parent-child、ordinal、offset 和 heading path。
3. 主题标签：生成、置信度、人工覆盖、索引字段和查询过滤。
4. 真实 reranker：可配置模型 profile、超时/重试/fallback，并记录模型版本和延迟。
5. 主题聚类和重复内容治理：离线任务、阈值、回滚和审计。
6. 物理删除/压缩：孤儿 key 扫描、版本保留策略、容量指标和 dry-run。

每一项都要新增单测、至少一个失败路径测试和可运行的验收命令；用固定数据集报告 Recall@K、MRR/NDCG、延迟和索引容量变化。

## 阶段 E：异步 AI Task 完整化

扩展现有 `com.hmdp.ai.task`，不要在 Controller 中复制执行逻辑：

- 接入长分析、批量店铺总结、运营报表；
- 统一任务参数、幂等键、进度、心跳、取消、超时、重试、死信和人工重放；
- 保留并验证 `REDIS_WRITE_ATOMIC` 批次、taskId compare-delete 和 `Error` 恢复语义；评估旧状态读取在多写者下的 CAS/Lua 协议；
- 确保服务重启后不会丢失 PENDING/RUNNING 任务；
- 评估 BlockingQueue 到 Redis Stream/consumer group 的迁移，先写 ADR 和兼容方案；
- 完善 SSE/WebSocket 事件持久化序列、Last-Event-ID、取消、慢消费者和断线恢复；SSE executor 必须继续在 `@PreDestroy` 释放。

验收至少包含：重复提交只生成一个活动任务、worker 崩溃可恢复、超过重试上限进死信、取消有终态、权限隔离、事件序号单调递增。

## 阶段 F：流式结构化质量

在现有事后审计外增加真正的增量质量层。当前实现只做有界缓冲、JSON 分类和完整收集后的质量审计，不得把它描述为逐 token 输出：

- 使用增量 JSON 状态机识别 incomplete/invalid/complete；
- 边接收边做 schema、枚举、证据 ID、大小和敏感信息检查；
- 明确 repair、fallback、截断、重放和审计记录；
- 对正常、截断、模型输出 Markdown、非法 JSON、证据越权分别测试；
- 记录首 token、完成延迟、repair 率、最终通过率、背压和慢消费者丢弃率，并用真实模型或可重复模拟流验证。

## 阶段 G：AI 服务拆分评估

暂不直接拆微服务。先输出 ADR，确认：领域边界、数据所有权、事件/HTTP 契约、租户认证、模型密钥、观测、部署和回滚。只有在容量、故障隔离或团队边界有量化依据时，才建立 `hmdp-ai-service` 原型，并先通过契约测试。

## 每阶段验收命令

```text
mvn -q clean test
mvn -q spotless:check
mvn -q -DskipTests package
mvn -q -Pfull-integration verify
```

Linux/Git Bash 环境额外执行：

```text
bash -n scripts/start-ai-infra.sh
bash -n scripts/stop-ai-infra.sh
bash -n scripts/verify-ai-platform.sh
./scripts/verify-ai-platform.sh
```

## 完成后的交付物

1. 代码和测试改动，列出每个文件及行为变化。
2. `docs/review/ai-project-audit-YYYY-MM-DD.md`：发现、修复、未完成项、风险、证据和环境缺口。
3. 本提示词对应的执行记录：每阶段命令、退出码、测试数量、跳过原因和日志位置。
4. 数据库变更说明：新迁移编号、checksum 处理、重复数据检查、回滚步骤。
5. 最终风险清单：按 P1/P2/P3 排序，明确 owner、前置条件和下一次复验命令。
6. 明确列出提交后外部资源删除持久重试、Redis 原子批次真实并发、Redis Stream consumer group/完整死信重放/取消协议/持久化事件序列/慢消费者仍未实现的范围。

## 停止条件

只有在所有必需测试通过、容器测试不再跳过、Redis/MySQL/MinIO/可执行 JAR 已真实验证、MySQL 8 桥接已由运维明确执行，并且审查报告中的 P1 项均关闭或有经批准的风险接受记录后，才可标记为完成。任何外部服务不可用时，标记为“阻塞/未验证”，继续完成不依赖该服务的工作。
