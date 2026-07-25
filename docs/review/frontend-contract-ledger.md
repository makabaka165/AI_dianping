# 前端契约台账（阶段 0）

> 更新时间：2026-07-25
> 规则：页面只接入真实接口；缺失接口记为 blocked/deferred，不得用假数据伪装完成。

## 环境记录

| 工具 | 版本 |
| --- | --- |
| Node.js | v24.13.0 |
| npm | 11.6.2 |
| Java | 17 |
| Maven | 3.9.16 |
| Docker | 29.5.2 |
| 包管理器 | npm（唯一锁文件 `frontend/package-lock.json`） |
| 前端目录 | `AI_dianping/frontend/`（仓库内并列后端） |
| 后端端口 | 8081（OpenAPI / 示例约定） |

## P0 契约启用

| 契约 | 路径 | 权限 | 状态 |
| --- | --- | --- | --- |
| Session bootstrap | `GET /api/v1/session/bootstrap` | 仅登录，无 tenant/workspace header | completed（新增） |
| Runnable agents | `GET /api/v1/runnable-agents` | `AGENT_RUN` + scope headers | completed（新增） |
| Run history | `GET /api/v1/agent-runs` | `AGENT_RUN`；普通用户仅自己，`RUN_INSPECT/ADMIN` 可看 scope 内全部 | completed（新增） |
| Create/get/cancel/retry/resume/events | `/api/v1/agent-runs/**` | `AGENT_RUN` | completed（既有） |
| Shop AI 访问策略 | `/api/shop-summary/**` | 仍要求 membership + `AGENT_RUN` | deferred：默认策略 1（仅授权成员可见），普通消费者普遍开放 blocked |

## 首条垂直闭环页面

| 页面 | 后端操作 | 权限 | DTO | 状态 |
| --- | --- | --- | --- | --- |
| `/login` | `POST /user/code`, `POST /user/login` | 匿名 | `Result` / token string | completed |
| Session restore | `GET /api/v1/session/bootstrap`, `GET /user/me` | 登录 | `SessionBootstrapResponse` / `UserDTO` | completed |
| `/studio/agents`（可运行列表） | `GET /api/v1/runnable-agents` | `AGENT_RUN` | `PageResponse<RunnableAgentResponse>` | completed |
| `/studio/runs` | `GET /api/v1/agent-runs` | `AGENT_RUN` | `PageResponse<AgentRunSummaryResponse>` | completed |
| `/studio/runs/:runId` | create + SSE + detail | `AGENT_RUN` | create/detail/event DTO | completed |

## 响应兼容现实

- 旧业务：`Result<T>`（`success/code/errorMsg/data`），code 为数字。
- AI v1：裸 DTO；拦截器拒绝时 `{success:false, code:"AI_*"}`。
- SSE：生命周期事件，最终结果以 Run 详情为准。
- `fallbackReason`：开放字符串。

## 明确不在默认授权内

- 改变 tenant/workspace/角色语义
- 数据库 migration
- 普通消费者自动获得 Shop AI
- 订单列表/详情、评论、商家“我的店铺”列表等 P1 契约
