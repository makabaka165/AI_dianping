# Java + AI 项目审查报告

- 审查日期：2026-07-23；Docker 复审日期：2026-07-24；收束复验日期：2026-07-24
- 项目：`AI_dianping`
- 范围：当前工作区中的 Java AI 子系统、Redis Stack 索引、Agent Runtime、Workflow、Flyway、Spring 装配、Compose/验证脚本和相关测试
- 结论：本地发布候选门禁已通过：默认测试、全部 Testcontainers 集成测试、Spring 上下文、可执行 JAR、Compose MySQL 8 历史修复和 Git Bash 完整平台 E2E 均有成功证据。正式生产发布仍需在生产库独立备份并显式执行/复核 MySQL 8 桥接，同时对 Redis v1/v2 运维迁移、外部删除失败重试和真正增量流式输出作上线决策。2026-07-24 收束复验（第 7 节）确认门禁可重复且复审无新增 P1/P2，后端阶段已收束，项目进入前端阶段

## 1. 审查口径

本次审查基于当前未提交工作区，不重置、不覆盖已有修改。判断分为三类：

- 已修正：已落代码或配置，并有本地自动化测试证据。
- 部分修正：代码防护已经补齐，但依赖 Docker、Redis Stack、MySQL 或可执行 JAR 的真实验证尚未完成。
- 未实现：属于后续产品或架构能力，不应伪装成已完成。

风险等级：

- P1：可能导致数据不一致、越权、迁移失败或核心链路错误。
- P2：可能导致启动失败、兼容性问题、可靠性下降或错误验证结果。
- P3：工程质量、可维护性或后续扩展风险。

## 2. 已修改内容中的问题与处理结果

| ID | 等级 | 已发现问题 | 影响 | 本轮处理 | 状态 |
| --- | --- | --- | --- | --- | --- |
| AUD-001 | P1 | Agent Run 先写 `COMPLETED`，后执行完成观察器 | 查询方可能先看到完成状态，但持久化记忆尚未可见 | 在两条完成路径中先执行 `RunCompletionObserver`，再提交 Run 完成状态，并增加调用顺序测试 | 已修正 |
| AUD-002 | P1 | 直接改写已存在的 Flyway 历史迁移会造成 checksum 不一致 | 已部署数据库启动时可能拒绝迁移 | 恢复历史迁移原内容，新增 `V20260723_01__repair_ai_schema_drift.sql` 做前向修复 | 已修正 |
| AUD-003 | P1 | Redis Stack 同名索引只吞掉 `index already exists`，不校验实际 schema | 错误前缀、字段类型或向量维度会被当成正常索引，可能产生跨版本数据混用 | `ensureIndex` 创建后或同名时均执行 `FT.INFO`，校验 HASH、唯一前缀、字段类型、TAG 分隔符、全文权重及 HNSW/FLOAT32/DIM/COSINE | 已修正并经 Redis Stack 容器验证 |
| AUD-004 | P1 | Chunk 批次 scope、维度、embedding 和 ACL 约束不足 | 混租户/混知识库数据、错误向量或无效 ACL 可能写入同一索引 | 增加批次 scope/维度、ID、内容、检索文本、finite embedding、质量分数和 ACL token 校验；ACL 仅允许 `all`、`workspace`、`user:<id>` | 已修正 |
| AUD-005 | P1 | 索引验证只对 chunkId 和 ACL 做弱校验 | 内容、检索文本或元数据漂移仍可能被判为有效 | 验证 tenant/workspace/KB/index/document/version/status/content/searchText/quality/timestamp；ACL 改为精确集合相等 | 已修正 |
| AUD-006 | P2 | `WorkflowValidator` 为规避 Spring 多构造器问题而失去受限执行器构造入口 | 扩展能力和能力矩阵测试退化 | 恢复四参数构造器，三参数构造器显式 `@Autowired`，拒绝空执行器集合并补 Spring/能力测试 | 已修正 |
| AUD-007 | P2 | 多个 Redis connection factory 没有默认主 Bean | Spring 基础设施可能因未限定注入而产生歧义 | 将业务 Redis connection factory 标记为 `@Primary`，向量索引继续使用显式 qualifier | 已修正 |
| AUD-008 | P2 | MySQL 主机端口、Connector 驱动和示例用户不一致 | 复制示例后可能连接 3306 或使用不存在的 `local_user` | Compose、README、YAML、脚本统一到主机端口 3307，驱动升级为 `com.mysql.cj.jdbc.Driver`，示例用户改为 `root` | 已修正 |
| AUD-009 | P2 | LangChain4j `@AiService` 扫描在可执行 JAR 中存在可用性风险 | IDE/classpath 可用但打包后可能缺少代理 Bean | 增加 `LegacyAiServiceConfig` 显式注册代理，并使用 `@ConditionalOnMissingBean`；上下文测试校验每个接口恰好一个代理，`ExecutableJarSmokeIT` 启动重打包 JAR 并探测 HTTP 端点 | 已修正并经容器/JAR 验证 |
| AUD-010 | P2 | SSE 验证脚本的首次事件 ID 正则匹配字面量 `\\r` | CRLF 响应可能被误判为非数字事件 ID | 修正为实际回车匹配；并完善重连后取消事件验证和后台 curl 清理 | 已修正，Shell 实机验证待补 |
| AUD-011 | P2 | Testcontainers 只跑 Flyway、未先载入旧版基础 schema | AI 迁移依赖旧表时会在空库失败，测试不能代表部署升级 | 新增共享 `IntegrationMySqlContainer`，先载入 `db/hmdp.sql`，再 baseline/兼容桥接/migrate；容器用例全部实际执行 | 已修正并经 MySQL 8 容器验证 |
| AUD-012 | P2 | `ai_index_version` 保存原始连字符索引名，Redis 实际使用规范化下划线名称 | 运维或后续运行时按元数据定位时会指向不存在的索引 | 抽出 `RedisKnowledgeIndexNaming` 供 Redis/JDBC 共用；新增稳定 hash v2 命名、v1 回退和 `V20260723_02__normalize_knowledge_index_metadata.sql`；READY/active 自定义物理名不被盲目改写 | 已修正代码与 MySQL 迁移路径；Redis 物理索引切换仍待容器验证 |
| AUD-013 | P1 | Oracle MySQL 8 不支持历史 `ADD COLUMN IF NOT EXISTS`，普通 Flyway 启动会在 `V20260720.03` 失败 | 新库初始化或存量升级会停在 AI schema 之前，且直接改历史文件会造成 checksum mismatch | 新增 `db/mysql8-compat` 替代脚本、精确 checksum 历史桥接 SQL 和显式 PowerShell 运维脚本；桥接校验 NULL/重复/异常历史并支持幂等复跑 | 已提供显式修复；应用自动启动仍不会代替桥接 |
| AUD-014 | P1 | 推荐结果的 `message/reason/suitableFor/uncertainty` 未经过统一文本质量校验 | 用户可见内容可绕过自我引用、危险表达和过度承诺规则 | `QualityGuard.validateRecommend` 对必填和可选文本逐项调用 `validateText`；补充非法表达和空值边界测试 | 已修正 |
| AUD-015 | P1 | 推荐项可重复推荐同一店铺、引用其他店铺证据，解析器也信任模型返回的店名/请求元数据 | 推荐排序、证据归属和用户看到的店铺身份可能被模型篡改 | 增加 shopId 去重和证据 shopId 归属校验；`StructuredOutputParser` 以候选店铺名、请求偏好和分类为权威值；补充解析器与质量回归测试 | 已修正 |
| AUD-016 | P1 | 非流式 `QAWorkflow` 调用三参数 `validateQA`，未传入请求中的目标 shopId | 模型返回其他店铺 shopId 时仍可能通过证据与文本校验，造成回答身份与请求不一致 | 改用带 expected shopId 的四参数质量门禁；回归测试用错误 shopId 触发一次 repair，并验证修复结果绑定原请求 shopId | 已修正 |
| AUD-017 | P1 | `QAWorkflow` 的流式计划使用 `analysisType=ask`，结构化规范化和 fallback 却只识别 `qa`；模型异常时还会降级为普通文本 | 真实流式 QA 即使返回合法 JSON 也会被判为不支持类型，异常路径则破坏客户端结构化契约 | 兼容识别 `ask/qa`，质量校验继续使用计划中的真实类型；结构化模型异常按 `MODEL_UNAVAILABLE` 生成类型化 JSON，并补正常、截断和异常回归 | 已修正 |
| AUD-018 | P1 | MySQL 8 兼容脚本对已知失败标记调用全局 `Flyway repair` | `repair` 可能同步更早成功迁移的 checksum/描述，掩盖无关历史漂移 | 在精确合同校验和 history 备份后，只删除两个完全匹配的失败行，并用 `ROW_COUNT()` 校验影响行数；静态契约禁止再次调用全局 repair | 已修正并在隔离 MySQL 8 失败记录场景验证 |
| AUD-019 | P2 | `KnowledgeIngestionIntegrationTest` 只拼接 Redis URL、只创建 MinIO bucket；混合检索也只测 workspace 正向命中 | 容器“通过”不能证明 Redis 连接、对象读写删除或 tenant/workspace/user 拒绝路径 | 改为真实 Redis `PING`、生产 MinIO 适配器上传/读取/删除；Redis Stack 测试增加 tenant、workspace、user 越权空结果，并新增可执行 JAR 烟雾测试 | 已修正并经容器验证 |
| AUD-020 | P1 | Compose 使用本地默认凭据时将 MySQL、Redis、Redis Stack 和 MinIO 发布到 `0.0.0.0` | 同一网络中的其他主机可能直接访问开发数据库、缓存、向量索引或对象存储 | 所有基础设施端口显式绑定 `127.0.0.1`，并增加静态合同和真实容器绑定验证 | 已修正并经 Compose 验证 |
| AUD-021 | P2 | 平台验证脚本只校验 MySQL 发布端口，且允许应用端点环境变量指向其他服务 | 脚本可能对 Compose 内部做 `PING`，应用却连接旧 Redis、外部数据库或其他 MinIO，产生错误通过 | 统一校验四类 Compose 回环端点；冲突的 DB、Redis、Memory、Vector 和 MinIO 覆盖值现在会在启动应用前失败 | 已修正并经 Bash 语法和端点门禁验证 |
| AUD-022 | P1 | Compose MySQL 存在两条 schema 已完整但成功 checksum 为已知预发布旧值的历史记录，原桥接脚本只能拒绝 | Flyway 在应用启动时拒绝校验，后续 5 个前向迁移无法执行 | 新增仅接受两条精确旧 checksum 的事务化 reconciliation SQL；校验元数据、64 个必需列、4 个唯一索引和每次更新的 `ROW_COUNT()`，先备份再更新，禁止全局 repair | 已修正；隔离 MySQL 8 正反场景、当前 Compose 和幂等复跑均通过 |
| AUD-023 | P1 | 公开文档删除只软删数据库并清 Redis chunk，不删除各文档版本的 MinIO 对象 | 用户删除后的原始内容仍长期保留，形成存储泄漏和数据保留风险 | 按 tenant/workspace 查询全部版本对象引用，事务提交后清理 Redis Stack 和 MinIO；单测覆盖多对象删除，E2E 校验对象计数恢复且检索不再返回已删 documentId | 已修正；外部删除失败的持久重试仍列入 R-010 |
| AUD-024 | P1 | E2E 使用当前 `mc` 不支持的 `find --type f`，容器内 `wc -l` 掩盖命令失败并返回 0 | MinIO 删除验证可在 CLI 报错时假阳性通过 | 改用 `mc ls --recursive --json`，在外层 `pipefail` 下逐行校验 `status=success,type=file` 并计数；静态合同禁止恢复旧命令 | 已修正并重新完整执行 E2E |
| AUD-025 | P1 | Flyway history 和人工全库备份曾放在 Maven `target` 下 | 后续标准 `mvn clean` 永久删除迁移前备份，审计文件无法长期留档 | history 默认目录迁到 Git 忽略的 `.local-backups/flyway-compat`，脚本拒绝任何 `target` 子目录；重新生成当前状态的全库/history 备份 | 默认路径已修正；迁移前文件已删除、不可作为回滚源 |

## 3. 风险与已关闭验证缺口

### R-001：本地完整平台 E2E 已闭环，真实外部模型兼容性仍需发布环境复验（P2）

Docker daemon 已可用，`mvn -Pfull-integration verify` 当前为 19 个 Failsafe suite、22 个测试，22 通过、0 failure、0 error、0 skipped。自动化证据覆盖旧 `hmdp.sql` 到最新 schema、Redis Stack schema/向量/全文/ACL、MinIO 对象上传读取删除、Spring 全上下文、AI 代理唯一性、可执行 JAR 和成功 checksum 漂移的正反场景。

`D:\Git\bin\bash.exe` 已执行 `scripts/verify-ai-platform.sh`：登录、默认 Agent、Agent Run、SSE replay/`Last-Event-ID` 重连、公开 API 上传/解析/摄取/删除、MinIO 与 Redis Stack 清理、发布、混合检索和 Artifact 权限拒绝全部通过。模型响应由本地 OpenAI-compatible stub 提供，因此发布环境仍需复验真实供应商的鉴权、限流、超时和响应兼容性。

### R-002：Redis v1 物理索引仍需受控迁移（P2）

新写入已经使用 `ai_kb_v2~` + UTF-8 SHA-256 前 32 位，`a-b` 与 `a_b` 不再碰撞；查询仍保留 v1 回退。`V20260723_04` 只把 `BUILDING/active=0` 的旧元数据切到 v2，READY/active 行不会在 Redis 物理索引未创建时被改名，因此现存 v1 索引仍需灰度切换和清理。

下一步需要在 Redis Stack 容器中验证双读、切换、回滚和孤儿索引清理；不能只凭 MySQL 元数据宣称物理迁移完成。

### R-003：前向修复迁移有数据前置条件（P2）

`V20260723_01` 只补缺失列和索引，不自动删除或合并重复业务数据。如果历史库中的 `deduplication_key` 或 dead-letter 组合键已经重复，唯一索引创建会失败；上线前仍必须执行重复数据审计并人工决定保留规则。兼容脚本已在隔离 Oracle MySQL 8 和当前 Compose MySQL 8.0.37 上执行，`V20260723_01` 到 `V20260723_05` 成功，且验证了 READY seed 保留 v1 名称；生产库仍需先做完整备份和同样的前置审计。

### R-004：默认 `mvn test` 不覆盖 Spring Boot 全上下文（P3）

`HmDianPingApplicationTests` 标记为 `integration`，Surefire 默认排除该组；因此 `mvn test` 仍只代表单元/组件基线。`full-integration` 已由 `ApplicationContextSmokeIT` 和 `ExecutableJarSmokeIT` 补上全上下文与归档启动证据，发布流水线必须继续执行该 profile，不能只跑默认测试。

### R-005：验证脚本已在主机 Git Bash 执行（已关闭）

Git 安装位于 `D:\Git`，其 `bin\bash.exe` 5.2.37 可用但未加入 `PATH`。已用该 Bash 完成语法检查和完整平台脚本，最终有效运行日志位于 `target/verify-ai-platform-1784860122-404`；此前一次 `mc find --type f` 的假阳性运行不计入通过证据。

### R-006：MySQL 8 普通应用启动仍不是自动兼容（P1）

历史文件保持原样是为了避免 checksum mismatch，因此只部署代码并直接启动全新 Oracle MySQL 8 库，仍会在 `V20260720.03` 失败。必须在首次启动前显式运行 `scripts/repair-mysql8-flyway-compatibility.ps1`；脚本只自动备份 Flyway history，不代替完整数据库备份，也不适用于 MariaDB。脚本不调用全局 repair，并分别处理精确失败/缺失记录、已完成历史和两条已知成功旧 checksum。当前 Compose 已将 checksum 修正为 `2143241596`、`814957484`，应用 5 个前向迁移至 `V20260723.05`，37 个迁移 validate 成功、失败记录为 0，幂等复跑通过。迁移前备份曾生成但因位于 `target` 被后续 `mvn clean` 删除；当前状态已重新持久备份到 `.local-backups/flyway-compat`，但它不能恢复迁移前状态。生产执行仍须在修复前独立备份和审批。

### R-007：任务状态和幂等修复尚缺真实 Redis 并发证据（P2）

任务 JSON 与状态索引写入已经统一使用 Redisson `REDIS_WRITE_ATOMIC` 批次；inflight 标记清理使用带 taskId 归属的 compare-delete，`Error` 异常路径保留标记并交给恢复扫描，避免旧任务删除新任务的幂等状态。当前测试覆盖本地行为，但 Redis 原子批次尚未在真实 Redis 中验证；旧状态读取仍在批次外，多写者场景后续还需要 CAS/Lua 优化。Redis Stream consumer group、完整死信重放、取消协议、持久化事件序列和慢消费者处理仍未完成。

### R-008：完成观察器、SSE 和流式结构化输出仍有边界（P2）

完成观察器异常现在会阻止 Run 标记为完成，并保留后续节点幂等恢复路径；SSE executor 已在 `@PreDestroy` 释放。流式 QA 的 `ask/qa` 类型和模型异常结构化 fallback 已修复，但实现仍是“完整收集后审计，再释放最终结果”，不是真正逐 token 增量释放，因此首 token 延迟、背压和慢消费者行为尚未达到发布承诺。

### R-009：当前 Compose MySQL 历史已完成审计（已关闭）

Redis 端口冲突已关闭：Compose 业务 Redis 默认发布到 `127.0.0.1:6381`，向量 Redis Stack 使用 `127.0.0.1:6380`，既有 `hmdp-redis-stack` 及其 `127.0.0.1:6379` 数据保持不变。所有 Compose 基础设施端口均限制到回环地址，四个长期服务均 healthy。Compose MySQL 的两个目标 checksum、最新版本、失败记录和幂等复跑均已核对，当前可作为本地发布候选验证基线。

### R-010：文档外部资源删除缺少持久重试（P2）

公开删除现在会在数据库提交后同步删除 Redis Stack chunk 和全部 MinIO 版本对象，并已通过真实 E2E；但外部服务若恰在提交后不可用，数据库删除不能回滚，现有 `DOCUMENT_DELETED` outbox 也尚无专用清理消费者。生产完善项是把对象/索引引用写入可重试清理事件，增加租约、死信、人工重放和孤儿扫描，避免瞬时故障留下不可自动恢复的外部资源。

## 4. 未完全实现的能力计划

### Phase 0：关闭发布阻断项

1. 生产执行 MySQL 8 桥接前完成全库备份、维护窗口、数据前置审计和 history backup 留档；本地 Compose 已完成审计并保留当前状态备份，但迁移前备份未留存，不能代替生产回滚点。
2. 本机 6379 端口隔离和 Compose MySQL checksum 审计已完成，未删除既有容器或数据卷。
3. Git Bash 完整 E2E 已完成；CI/发布环境继续执行同一脚本并保存日志。
4. 公开 API 的摄取、发布、混合检索、权限拒绝和文档删除/MinIO/索引清理 E2E 已完成；下一步补 R-010 的持久重试。

### Phase 1：完成 RAG 数据与检索质量

1. 文档清洗：编码、模板噪声、重复段、HTML/Markdown 结构和敏感数据处理。
2. 更细粒度分句切片：中英文标点、标题边界、表格/PDF 结构和 parent-child chunk。
3. 主题标签与可解释元数据：标签生成、置信度、人工修订和索引字段。
4. 接入专用真实 reranker，并保留 fallback RRF；建立离线 Recall@K、MRR、NDCG 和延迟基线。
5. 主题聚类与重复内容治理。
6. 向量索引物理删除、压缩、孤儿 key 扫描和容量告警。

### Phase 2：完成异步 AI Task 可靠性

1. 接入长分析、批量店铺总结和运营报表任务。
2. 在已有 `REDIS_WRITE_ATOMIC`、compare-delete 和恢复扫描基础上，评估 BlockingQueue 到 Redis Stream/consumer group 的迁移，补重试、租约、死信、人工重放和幂等键。
3. 完整实现取消、心跳、进度、超时和服务重启恢复，并把状态读取与写入纳入可验证的 CAS/Lua 协议。
4. 统一 SSE/WebSocket 推送协议，持久化事件序列并补断线重连、Last-Event-ID、取消和慢消费者测试。

### Phase 3：流式结构化输出质量

1. 保留现有有界缓冲、JSON 分类和最终质量校验，改为真正逐 token 增量释放并明确背压。
2. 边接收边做 schema、枚举、证据 ID、大小和敏感信息校验，避免只能在完整收集后发现问题。
3. 定义截断、repair、fallback、重放和审计策略，并覆盖观察器失败后的幂等恢复。
4. 用固定数据集比较成功率、repair 率、首 token 延迟、完整响应延迟和慢消费者丢弃率。

### Phase 4：评估独立 AI 服务

当前应继续保持模块化单体，先稳定 Port/Adapter、事件契约、租户上下文和可观测性。只有在团队边界、部署节奏、容量或故障隔离确有需求时，再拆出 `hmdp-ai-service`；拆分前必须先做契约测试和数据所有权 ADR。

## 5. 验证证据

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `mvn -q clean test` | 通过 | 186 个 suite，692 个测试，0 failure，0 error，0 skipped |
| `mvn -q -Dtest=RedisStackKnowledgeIndexAdapterTest,JdbcKnowledgeRepositorySqlContractTest test` | 通过 | 索引 schema 与物理名称修正回归 |
| `mvn -q spotless:check` | 通过 | 配置范围内 Java 格式通过 |
| `mvn -q -DskipTests package` | 通过 | 可执行包构建通过 |
| `mvn -q -Pfull-integration verify` | 通过 | 19 个 suite、22 个测试，0 failure、0 error、0 skipped；包含 MySQL 8、Redis/Redis Stack、MinIO、全上下文、可执行 JAR 和成功 checksum 漂移正反场景 |
| `mvn -q -Dtest=QAWorkflowTest,ChatWorkflowStreamTest,SchemaConsistencyTest test` | 通过 | `ask` 流式结构化 QA、类型化模型异常 fallback、shopId 绑定和精确 Flyway history 清理契约 |
| `mvn -q -Pfull-integration -Dit.test=KnowledgeIngestionIntegrationTest,HybridRetrievalIntegrationTest verify` | 通过 | Redis PING、MinIO 上传/读取/删除、向量/全文检索及 tenant/workspace/user 拒绝路径 |
| `ExecutableJarSmokeIT` | 通过 | 隔离 MySQL/Redis 上启动打包 JAR，HTTP `/actuator/info` 返回 200 后正常回收进程 |
| `docker compose -f docker-compose.ai.yml config --quiet` | 通过 | Compose 配置可解析 |
| `docker info` | 通过 | Docker Server 29.5.2 可用 |
| `docker compose -f docker-compose.ai.yml up -d --wait` | 通过 | MySQL、业务 Redis `6381`、Redis Stack `6380`、MinIO 和 MinIO 初始化服务全部健康且仅绑定 `127.0.0.1`；既有 `6379` 容器未修改 |
| `D:\Git\bin\bash.exe -n scripts/verify-ai-platform.sh` | 通过 | 主机 Git Bash 5.2.37 语法检查通过 |
| `D:\Git\bin\bash.exe -lc 'bash scripts/verify-ai-platform.sh'` | 通过 | 登录、Agent Run、SSE 重连、上传/摄取/删除、MinIO/Redis 清理、发布、混合检索和 Artifact 权限均通过；有效日志目录 `target/verify-ai-platform-1784860122-404` |
| `PowerShell Parser::ParseFile(scripts/repair-mysql8-flyway-compatibility.ps1)` | 通过 | 严格模式脚本语法通过 |
| 隔离 MySQL 8.0.46：全新库桥接 | 通过 | `hmdp.sql` -> 安全基线 -> 兼容历史 -> `V20260723.05`；两个历史 checksum 分别为 `2143241596`、`814957484` |
| 隔离 MySQL 8.0.37：失败记录恢复 | 通过 | 人工登记一条精确 `V20260720.03` 失败记录；脚本备份 history、删除 1 条目标行、登记两个发布 checksum，最终到 `V20260723.05`，失败记录为 0 |
| 隔离 MySQL 8.0.36：成功旧 checksum reconciliation | 通过 | 两条已知旧 checksum 原子更新成功；未知 checksum 被拒绝且无半更新 |
| 隔离 MySQL 8.0.46：幂等复跑 | 通过 | 已完成桥接的数据库不重复执行兼容 DDL，普通 Flyway 校验成功 |
| 当前 Compose MySQL 8.0.37：reconciliation、迁移、复跑 | 通过 | 最终 37 条成功历史、0 失败、最新 `V20260723.05`；迁移前备份曾生成但已被后续 `mvn clean` 删除，不可作为回滚源 |
| 当前 Compose 当前状态持久备份 | 通过 | history TSV SHA-256 `95E983E193640E041A537F0E02876046DBFAA3260274073E043B8518DCB92C05`；全库 SQL SHA-256 `5FE77137CDB8EA763AE5AA903C4CBE25255A2FAC753A5E96A18C7B4E8C0E94EC`；均位于 `.local-backups/flyway-compat` |

## 6. 发布前最低验收标准

- `mvn clean test`、`mvn -Pfull-integration verify`、`mvn spotless:check`、`mvn -DskipTests package` 全部退出码为 0。
- Failsafe 报告中容器相关测试 `skipped=0`。
- 新旧数据库升级均成功，MySQL 8 先完成显式桥接且 Flyway 无 checksum mismatch；`V20260723_02` 的索引元数据与 Java 命名结果一致，`V20260723_04` 不改 READY/active 的旧物理名。
- Redis Stack schema 校验、向量/全文检索和自动化 ACL 隔离已通过；v1/v2 生产迁移演练仍需完成。
- 任务 JSON/状态索引原子批次、inflight compare-delete、完成观察器失败恢复和 SSE executor 释放均有单测；真实 Redis 并发和容器路径仍须通过。
- 流式结构化输出必须从“完整收集后审计”升级为真正增量释放，并给出首 token 与慢消费者证据。
- 可执行 JAR 已启动成功，全上下文测试确认每个 AI Service 接口恰好一个 Bean。
- `verify-ai-platform.sh` 在 Linux/Git Bash 环境完整通过。
- 对 R-002、R-003、R-006 和 R-010 给出上线前决策、完整备份和回滚步骤。

## 7. 2026-07-24 收束复验与阶段判定

### 7.1 门禁复跑

同日对当前工作区完整复跑四项门禁，日志留档 `.local-backups/closure-gates-20260724.log`：

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| `mvn -q clean test` | 0 | Surefire 186 suites / 692 tests / 0 failure / 0 error / 0 skipped |
| `mvn -q spotless:check` | 0 | 通过 |
| `mvn -q -DskipTests package` | 0 | 通过 |
| `mvn -q -Pfull-integration verify` | 0 | Failsafe 19 suites / 22 tests / 0 failure / 0 error / 0 skipped |

复验时点 Compose 五个服务（MySQL、业务 Redis、Redis Stack、MinIO、minio-init）全部 healthy；`git diff --check` 无空白错误；新增/修改行秘密扫描未发现真实凭据。E2E 有效日志已从 `target/verify-ai-platform-1784860122-404` 备份到 `.local-backups/e2e-logs/verify-ai-platform-1784860122-404`，`target` 内副本会随 `mvn clean` 删除，后续引用以 `.local-backups` 为准。

### 7.2 独立复审结论

- workflow/质量层（ChatWorkflow、QAWorkflow、CompareWorkflow、RecommendWorkflow、StreamWorkflowPlan、QualityGuard、AIResultQualityService、StructuredOutputParser、WorkflowValidator 及其测试）完成与 HEAD 基线逐项对照的全量复审：AUD-014～AUD-017 防线在解析器、质量门禁、流式编排三层闭合，`ask/qa` 双识别、类型化 MODEL_UNAVAILABLE fallback、shopId 绑定与证据归属校验均无回归，无新增 P1/P2。
- 知识/向量/迁移、runtime/task/SSE、脚本/配置三个分片按定向核查结合全量测试证据确认：完成观察器先于 `runs.complete`（两条路径）、文档删除按 tenant/workspace 收集全部版本对象并在事务提交后清理 Redis Stack 与 MinIO、Compose 端口全部回环绑定且与 `.env.example` 默认值一致、两条历史 checksum 在 PowerShell 脚本、SQL 契约、集成测试与静态契约测试中完全一致。

### 7.3 新增 P3 记录项（不阻断收束）

| ID | 等级 | 描述 | 处置 |
| --- | --- | --- | --- |
| AUD-026 | P3 | 流式 directText 的确定性短消息（如“暂无评价数据”）会被 10 字最小长度审计拒绝并降级为错误的“服务不可用”文案，与非流式行为不一致；该行为为基线遗留，非本轮引入，且缺少流式测试覆盖 | 随 Phase 3 流式改造一并修复：directText 走类型化 JSON 并豁免确定性系统消息的最小长度审计 |
| AUD-027 | P3 | 非流式推荐无条件把 rank 重排为 1..n，流式仅在截断时重排；模型返回合法非连续 rank 时两者输出不一致 | 随 Phase 3 统一归一化时机 |
| AUD-028 | P3 | `fallbackReason` 存在枚举外字符串值：`OUTPUT_TRUNCATED`、`STRUCTURED_OUTPUT_REJECTED/INCOMPLETE/INVALID`；服务端无 `valueOf` 反解消费者，但前端若按枚举白名单解析需同步 | 已在前端对接契约中列出；前端按开放字符串处理该字段 |
| AUD-029 | P3 | `MAX_STREAM_PARTS=4096` 硬编码，逐字符分片的长回答可能早于 100k 字符上限被判截断并整体降级（降级路径本身优雅） | 随 Phase 3 流式改造调整为可配置阈值 |

### 7.4 阶段判定

本地发布候选门禁保持闭环、复审无新增 P1/P2：**后端阶段判定为已收束，工作区改动随本判定提交冻结，项目进入前端阶段。**生产发布仍受第 6 节最低验收标准与 R-002、R-003、R-006、R-010 约束；后端后续跟进（生产桥接、外部删除持久重试、Redis v1/v2 运维迁移、异步任务与流式增量质量）继续按 `ai-project-next-step-prompt.md` 执行，不阻塞前端开发。
