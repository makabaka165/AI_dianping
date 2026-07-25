# 前端阶段状态（2026-07-25）

## 验证命令

### 后端定向测试
```text
mvn -q -Dtest=SessionBootstrapApplicationServiceTest,AgentDefinitionListRunnableTest,AgentRunListHistoryTest,SessionBootstrapInterceptorExclusionTest,AgentRunApplicationServiceTest,AiAuthorizationServiceTest test
```
退出码：0

### 前端
```text
npm run lint        # 0，无 warning
npm run typecheck   # 0
npm run test        # 0，5 files / 13 tests
npm run build       # 0
npm run test:e2e    # 0，3 smoke passed；真实后端开关关闭时 3 skipped
PLAYWRIGHT_REAL_BACKEND=true npm run test:e2e
                    # 0，3 smoke + 1 desktop 真实后端链路 passed；2 非 desktop skipped
```

## 完成项

### 阶段 0
- 契约台账：`docs/review/frontend-contract-ledger.md`
- P0 bootstrap / runnable agents / run history 后端契约与测试
- OpenAPI 首条垂直链路 schema 补强
- Shop AI：默认策略 1（仅授权成员），普通消费者普遍开放 `blocked`

### 阶段 1
- `frontend/` Vite + React + TypeScript
- SessionScope / HttpTransport / StreamTransport
- 三壳层 + guards + 登录页
- 单元测试覆盖传输层、session、输出映射

### 阶段 2
- 可运行 Agent 列表
- Run 创建 / 历史 / 详情
- SSE 观察 + 终态详情刷新
- ResponseBlockRenderer（未知块安全降级，fallbackReason 开放字符串）
- 真实后端 Playwright：mock SMS 登录、Session bootstrap、Agent 列表、Run 创建、SSE feedback.required、Run 历史持久化
- desktop/mobile 响应式复核：页面无全局横向溢出，Agent 与 Run 表格在窄屏切换为完整信息卡

## 未做 / 阻塞

| 项 | 状态 |
| --- | --- |
| 消费者店铺/社区/优惠券完整页 | deferred（阶段 3） |
| Shop AI 普通消费者开放 | blocked，待产品决策 |
| 商家“我的店铺”闭环 | blocked，缺接口 |
| Prompt/Model/Knowledge 完整管理 | deferred（阶段 4） |
| 首条 Agent 垂直链路 Playwright E2E | completed（真实后端；外部模型调用不在该用例范围） |
| Prompt/Model/Knowledge 等管理链路 E2E | deferred（对应页面尚未实现） |
| 全量 `mvn test` | not verified（本轮只跑定向测试） |

## 本地访问

- 前端：`http://127.0.0.1:5173`
- 后端代理目标：`http://localhost:8081`
- 登录后进入 `/studio/agents`、`/studio/runs` 验证 AI 闭环（需有效 membership + `AGENT_RUN`）
