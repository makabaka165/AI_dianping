# 前端项目完整执行提示词

> 状态（2026-07-24）：前端架构基线已经拟定，具体实施等待用户明确批准。本文件用于固化下一轮前端工作的完整上下文、执行顺序和验收标准；仅创建本文件不代表已经授权开始开发。
>
> 使用方式：用户批准后，将本文件完整交给下一轮 Codex 或开发者执行。除非用户另行缩小范围，执行者应持续完成所有未受阻阶段，不要在只输出计划后停止。

## 1. 角色与最终目标

你是本项目的高级前端与全栈工程师。你需要在现有 `AI_dianping` Spring Boot 模块化单体之上，从零建立生产质量的 Web 前端，并让消费者、商家和 AI Studio/运营人员能够使用后端已经真实具备的能力。

最终目标不是制作静态演示页，而是交付可运行、可测试、可维护、权限隔离正确的前端应用。所有页面都必须接入真实接口；接口缺失时，先补齐本文明确允许的最小后端契约或将页面标记为阻塞，不得用假数据伪装完成。

后端核心 runtime、知识检索、工作流执行、数据迁移和生产运维阶段已经收束。前端阶段不得借机重构这些已经冻结的后端模块。为了让前端能够初始化身份、列出可运行资源和形成基本闭环，可以补充本文列明的查询契约及其测试；任何改变业务语义、数据库结构、租户模型或权限模型的后端改动必须先报告并等待用户决定。

## 2. 事实来源与开始前检查

以下文件是执行时的事实来源，发生冲突时以代码、测试和实际 HTTP 行为为准，并同步修正文档：

- 后端收束结论：`docs/review/ai-project-audit-2026-07-23.md`
- 后端后续工作：`docs/review/ai-project-next-step-prompt.md`
- Agent 平台架构：`docs/architecture/agent-platform-overview.md`
- 安全模型：`docs/architecture/security-model.md`
- Agent runtime：`docs/architecture/agent-runtime.md`
- 工作流 runtime：`docs/architecture/workflow-runtime.md`
- OpenAPI：`docs/api/openapi.yaml`
- 可执行请求示例：`docs/examples/`
- 实际接口实现：`src/main/java/com/hmdp/controller` 与 `src/main/java/com/hmdp/ai/api`

当前已知现状（执行时必须重新核对）：

- 仓库尚无 `package.json` 或前端骨架；
- `/user/me` 只返回 `id`、`nickName`、`icon`，没有角色、权限或 membership；
- 默认 workspace 的种子 membership 当前只覆盖用户 `1`，不能把该用户或 `default` scope 硬编码进前端；
- `/api/shop-summary/**` 同样经过 `AiPermissionInterceptor`，现阶段仍要求 `AGENT_RUN` 和有效 workspace membership，不能把 legacy 接口误认为普通消费者天然可用；
- `/api/v1/**` 同时要求 Bearer token、`X-Tenant-Id`、`X-Workspace-Id`；
- OpenAPI 虽已覆盖主要路径，但多数成功响应仍是宽泛 `object`，不足以生成可靠客户端；
- 成功响应同时存在裸 DTO 和旧 `Result<T>`，错误码存在数字/字符串两类，部分业务失败仍返回 HTTP 200；
- Agent Run SSE 主要发送生命周期事件，最终结果在 Run 详情中读取；
- 评论、订单列表/详情、商家“我的店铺”列表，以及部分 Workflow、Tool、Knowledge、Evaluation 列表/版本操作仍缺少闭环接口；
- Workflow DTO 不持久化画布坐标；`fallbackReason` 存在已知枚举外字符串值。

开始实施前必须：

1. 运行 `git status --short --branch`，识别并保留用户已有改动。
2. 确认仓库中是否仍然没有 `package.json`、前端目录或新的接口实现；不要机械覆盖用户在本提示词之后添加的内容。
3. 读取上述事实来源和相关 Controller、请求 DTO、响应 DTO、权限声明。
4. 记录 Node.js、包管理器、Java、Maven 和 Docker 的实际版本。
5. 建立“页面 -> 后端操作 -> 权限 -> 请求/响应 DTO -> 完成状态”的契约台账。
6. 先验证最小后端启动方式和基础健康状态，再开始联调；不要一开始运行全部容器集成测试。

## 3. 不可违反的约束

1. 保留工作区已有改动；禁止使用 `git reset --hard`、`git checkout --` 或无边界批量删除。
2. 未经用户要求不要提交 Git commit，不要修改历史 Flyway migration，不要提交 `.env`、令牌、手机号、模型密钥或其他敏感信息。
3. 所有 `/api/v1/**` 业务请求必须携带 Bearer token、`X-Tenant-Id` 和 `X-Workspace-Id`。唯一例外是尚未选择 scope 的 bootstrap 接口；它仍必须要求登录，并在后端显式实现该例外。
4. UI 隐藏不是授权。后端 401/403 必须保持最终裁决，前端只依据 bootstrap 返回的权限决定导航和操作可见性。
5. tenant/workspace 必须进入所有 AI 服务端状态查询键。切换 scope 时取消旧请求、关闭旧 SSE、清除旧 scope 缓存，再加载新 scope 数据。
6. 不得使用原生 `EventSource` 连接需要自定义请求头的 SSE。统一使用 Fetch-SSE 传输模块。
7. `resumeToken` 只能存在于内存中，不得写入 Local Storage、Session Storage、IndexedDB、日志、埋点、URL 或错误报告。
8. `fallbackReason` 按开放字符串处理。未知值正常显示通用降级说明，不得因枚举解析失败导致页面崩溃。
9. 不得把 Agent Run 生命周期 SSE 伪装成逐 token 输出。最终结果以 Run 详情为准。
10. 不得根据当前宽泛的 OpenAPI 直接生成并信任完整客户端。先补全具体 schema，再决定是否生成。
11. 不得为缺失的订单列表、订单详情、评论、商家“我的店铺”列表或其他接口制造假数据页面。
12. 不要把所有状态塞进全局 store。服务端状态归 TanStack Query，表单状态归 React Hook Form，URL 状态归 Router，只有跨页面且非服务端的少量 UI 状态进入 Zustand。
13. 手工编辑使用 `apply_patch`；格式化器和脚手架的机械生成可以使用其标准命令。不要重写与任务无关的文件。
14. 每个阶段先完成契约和测试，再扩展页面数量。修复必须集中在模块内部，不能在多个页面复制兼容逻辑。

## 4. 已批准的前端架构基线

### 4.1 应用形态

建立一个 React SPA，并在同一应用中提供三个路由壳层：

| 路由前缀 | 壳层 | 用户 | 目标 |
| --- | --- | --- | --- |
| `/` | Consumer | 普通消费者 | 店铺发现、社区、优惠券、用户关系和店铺 AI 助手 |
| `/merchant` | Merchant | 商家用户 | 店铺经营、内容和优惠券管理；仅开放后端真实支持的能力 |
| `/studio` | Studio | AI 管理员、运营人员 | Agent、Run、Prompt、模型、知识、审批、观测及后续高级能力 |

当前不要拆微前端或三个独立部署。登录、scope、传输、错误、权限和 UI 基础设施高度共享，仓库也没有独立团队或发布边界的证据。Workflow 画布、Monaco 等重依赖通过路由级懒加载隔离。

### 4.2 技术基线

- React + TypeScript + Vite
- React Router
- TanStack Query
- React Hook Form + Zod
- Zustand，仅用于少量跨页面 UI 状态
- Fetch + `eventsource-parser`
- DOMPurify
- Lucide 图标
- CSS variables + CSS Modules；除非有单独决策记录，不默认引入 Tailwind 或整套后台 UI 框架
- Vitest + Testing Library + MSW
- Playwright，用于关键链路、响应式和视觉检查
- `@xyflow/react` 与 Monaco 只在 Workflow/复杂编辑阶段引入

优先使用当前稳定版本并生成锁文件。选择 npm、pnpm 或其他包管理器前先检查本机和仓库约定；一旦选定，不得混用锁文件。

### 4.3 仓库放置与新建方式

前端需要新建文件夹，但必须新建在现有 `AI_dianping` 仓库内部，并与 Maven 后端并列：

```text
AI_dianping/
  pom.xml
  src/                         # 现有 Spring Boot 后端
  docs/
  scripts/
  frontend/                    # 新建的唯一前端工程
    package.json
    src/
```

执行规则：

1. 先确认仓库中没有用户后来建立的前端目录；如果已有，则评估并沿用，不能再创建第二套工程。
2. 默认在 `AI_dianping/frontend/` 初始化 Vite React TypeScript 工程。
3. 不要把源代码放到 `src/main/resources/static`，不要放到仓库外的同级 `frontend/`，也不要在仓库内的 `frontend/` 中再次执行 `git init`。
4. 根目录 `pom.xml` 在第一阶段继续只管理后端；前端由自己的 `package.json` 和唯一锁文件管理。是否合并成单 JAR 构建属于后续部署决策，不是脚手架阶段的默认动作。
5. `frontend/node_modules/`、`frontend/dist/`、`frontend/playwright-report/`、`frontend/test-results/` 和本地 `.env.*` 必须加入忽略规则；不得提交构建产物和本地凭据。
6. 不要预先创建所有空业务目录。只建立基础设施和当前阶段会实现的模块，后续目录随真实业务闭环逐步加入。

### 4.4 完整前端文件布局

目标布局如下。标记为“按阶段增加”的目录不用在初始化时创建空壳：

```text
frontend/
  .env.example
  index.html
  package.json
  package-lock.json             # 或其他唯一锁文件，不得并存
  vite.config.ts
  vitest.config.ts
  playwright.config.ts
  eslint.config.js
  tsconfig.json
  tsconfig.app.json
  tsconfig.node.json
  public/                       # 必须原样复制的少量公开静态文件
  src/
    main.tsx                    # 唯一浏览器入口
    vite-env.d.ts
    assets/                     # 经 Vite 构建的图片、字体等资源
    app/
      App.tsx
      router.tsx                # 聚合三套路由，不写业务请求
      providers/
        AppProviders.tsx
        QueryProvider.tsx
        SessionProvider.tsx
      routes/
        consumer-routes.tsx
        merchant-routes.tsx
        studio-routes.tsx
        guards/
          RequireSession.tsx
          RequirePermission.tsx
          RequireScope.tsx
      layouts/
        ConsumerLayout.tsx
        MerchantLayout.tsx
        StudioLayout.tsx
      styles/
        tokens.css
        globals.css
        reset.css
    modules/
      account/                  # 第一阶段
      agents/                   # 第二阶段
      runs/                     # 第二阶段
      shop/                     # 第三阶段
      community/                # 第三阶段
      voucher/                  # 第三阶段
      shop-assistant/           # 第三阶段
      merchant/                 # 第五阶段
      operations/               # 第五阶段
      models/                   # 第四阶段
      prompts/                  # 第四阶段
      knowledge/                # 第四阶段
      approvals/                # 第四阶段
      workflows/                # 第六阶段
      tools/                    # 第六阶段
      evaluations/              # 第六阶段
      mcp/                      # 第六阶段
      memory/                   # 第六阶段
    shared/
      config/                   # 环境配置解析与校验
      contracts/
        common/                 # 分页、错误、scope 等真正共享的契约
        generated/              # OpenAPI 稳定后才生成，禁止手改
      transport/
        http/
        download/
      stream/
      session/
      query/
      ui/                       # 跨两个以上模块复用的基础 UI
      lib/                      # 无业务语义的纯函数
    test/
      setup.ts
      fixtures/                 # 从真实 DTO 提炼的固定数据
      mocks/                    # MSW handlers 与测试 adapters
  tests/
    e2e/
      fixtures/
      pages/                    # 只在确实降低重复时使用 page objects
      specs/
```

`public/` 只放无需构建处理、必须保持文件名的资源；可以被 TypeScript import 的资产应放入 `src/assets/`。真实店铺、用户、文档和 Agent 资源优先来自后端，不在仓库中复制一份模拟业务素材。

### 4.5 单个业务模块的内部模板

每个业务模块私有实现默认不对外暴露。以 `agents` 为例：

```text
src/modules/agents/
  index.ts                      # 小而明确的公共 interface
  route.tsx                     # 可供 Router 懒加载的公开路由入口
  contracts/
    agent-contract.ts           # 该模块拥有的 DTO 与领域视图类型
  adapters/
    http-agent-adapter.ts       # 后端 HTTP adapter
    in-memory-agent-adapter.ts  # 测试 adapter；存在真实替换需求时才建立 seam
  queries/
    agent-query-keys.ts
    agent-queries.ts
    agent-mutations.ts
  schemas/
    agent-form-schema.ts
  pages/
    AgentListPage.tsx
    AgentDetailPage.tsx
  ui/
    AgentTable.tsx
    AgentForm.tsx
  lib/
    map-agent-response.ts       # 纯映射或该模块内部规则
  __tests__/
    agent-interface.test.tsx
```

不是每个模块都必须机械拥有上述全部目录。没有替换需求时不要为了形式创建 adapter；没有纯逻辑时不要创建空 `lib/`。测试以模块公共 interface 和用户可观察行为为表面，而不是逐个私有文件建立镜像测试。

命名规则：

- React 页面和 UI 文件使用 `PascalCase.tsx`；非 React 文件使用描述性 `kebab-case.ts`；测试使用 `.test.ts` 或 `.test.tsx`。
- 目录使用 `kebab-case`；hook 使用 `useXxx`；Query key 只能由对应 factory 创建。
- 避免含义模糊的 `utils.ts`、`helpers.ts`、`types.ts`、`api.ts` 大文件，文件名应说明它拥有的规则。
- `index.ts` 只导出调用者真正需要的 interface，不建立跨全项目的大型 barrel，也不重新导出模块全部私有实现。
- Router 只能从模块的 `index.ts` 或 `route.tsx` 公共入口加载页面；其他模块不得深层 import `pages/`、`queries/` 或 `adapters/`。

### 4.6 依赖方向与数据流

依赖方向固定为：

```text
app  -> modules -> shared
app  ------------> shared

shared -X-> modules
shared -X-> app
module A -X-> module B 的私有目录
```

一次典型读取应沿以下方向发生：

```text
route -> page -> module query/interface -> module adapter
      -> shared HttpTransport -> Spring Controller
```

响应返回时由拥有该业务语义的模块完成 DTO 校验和 view model 映射。页面只消费已经归一化的数据，不直接处理 `Result<T>`、HTTP headers 或服务器字段兼容。

跨模块协作优先放在 `app/` 的用例编排处，或依赖对方公开的窄 interface。只有两个以上模块使用且语义完全一致的内容才进入 `shared/`；“可能以后复用”不是上移理由。`shared/ui` 只包含真正通用的按钮、表单控件、对话框、表格骨架和状态视图，不包含 Agent、店铺或优惠券业务规则。

TypeScript 默认只建立 `@/` 指向 `src/` 的路径别名，避免维护多套别名。禁止前端 import Java 源码或复制后端实体；网络 DTO 是传输契约，前端 view model 由拥有它的业务模块定义。

### 4.7 实际创建顺序

获得用户批准后，按以下顺序真正创建文件，不要一次铺开全部页面：

1. 在仓库根目录创建 `frontend/` Vite React TypeScript 工程，确定包管理器和唯一锁文件。
2. 建立 TypeScript、ESLint、Vitest、Playwright、环境变量校验、CSS tokens 和测试 setup。
3. 建立 `shared/config`、`HttpTransport`、`StreamTransport`、`SessionScope`、通用错误和 Query key 基础设施及其测试。
4. 建立 `app/providers`、Router、三个 Layout 和权限/scope guards。
5. 只创建 `account`、`agents`、`runs` 三个首批业务模块，完成登录和 Agent Run 垂直闭环。
6. 通过阶段门禁后，再按消费者、Studio、商家、高级能力的顺序增加对应目录。
7. 每新增一个模块，同时增加 route、真实 adapter、状态处理和测试；禁止先批量生成空页面再补逻辑。

如果选用 npm，初始化命令可以是：

```text
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install
```

执行时以当时稳定且兼容的脚手架为准。脚手架生成后立即审查其文件，不保留示例 Logo、计数器、默认演示 CSS 或无用资产。

### 4.8 本地开发、构建与部署布局

开发时前后端分进程运行：Spring Boot 保持现有端口，Vite 使用可用的本地端口。浏览器端请求使用相对路径；`vite.config.ts` 将现有 `/api`、`/user`、`/shop`、`/shop-type`、`/blog`、`/follow`、`/voucher`、`/voucher-order`、`/upload`、`/document`、`/admin` 等后端前缀代理到由环境变量配置的后端地址。不要在业务文件中硬编码 `localhost` 或端口。

构建结果固定输出到 `frontend/dist/`，该目录可随时删除和重建，不进入 Git。生产基线是把 `dist/` 作为独立静态站点部署，并通过同源反向代理把后端路径转发到 Spring Boot，同时为前端路由配置 SPA fallback；后端路径必须优先于 fallback，避免把 JSON 请求错误返回为 `index.html`。

不要在第一阶段手工复制 `dist/` 到 `src/main/resources/static`。如果以后明确要求单 JAR 部署，应先记录 ADR，再用可重复的 Maven/Node 构建步骤自动装配，并确保 Maven 后端测试不隐式依赖本机已有 `node_modules`。

CI 中前端应作为独立 job 执行 install、lint、typecheck、unit 和 build；E2E job 在启动真实后端或明确的测试 adapter 后执行。根 README 最终补充前端开发、代理、构建和联调命令。

模块之间通过明确 interface 交互。页面不得直接解析旧响应包装、拼 Authorization/header、管理 SSE 重连或读取 token 存储。

## 5. 必须形成的深模块

以下模块需要用小 interface 隐藏复杂 implementation，并作为主要测试面：

### 5.1 `SessionScope`

负责登录态恢复、bootstrap、token 生命周期、当前 tenant/workspace、权限判断、scope 切换和退出登录。Bearer token 的具体存储只能由该模块知道；在当前后端没有 HttpOnly cookie 能力时，默认采用内存 + `sessionStorage` 的受控 adapter，禁止散落读取。若后端以后支持 HttpOnly cookie，只替换 adapter。

它至少向调用者提供：当前 session 快照、`bootstrap()`、`selectScope()`、`can(permission)` 和 `logout()`。切换 scope 的取消请求、关闭流和缓存清理属于模块 implementation，不暴露给页面处理。

### 5.2 `HttpTransport`

统一完成 base URL、JSON/表单/文件请求、Authorization、scope headers、AbortSignal、超时、请求 ID 和响应解析。它必须兼容：

- 裸 DTO；
- 旧 `Result<T>`；
- 数字或字符串错误码；
- HTTP 200 但 `success=false`；
- 204、空 body、文件下载和非 JSON 错误。

所有失败统一转换为：

```ts
interface CanonicalError {
  code: string;
  message: string;
  requestId?: string;
  fieldErrors?: Record<string, string>;
  retryable: boolean;
  status?: number;
}
```

页面不得再次判断 `response.ok`、`success` 或不同错误包装。

### 5.3 `StreamTransport`

统一使用 Fetch 连接 SSE，支持自定义 headers、`Last-Event-ID`、AbortSignal、分片解析、有界指数退避、重复事件去重和明确终止。401/403、业务终态、用户取消和不可重试 4xx 不重连。

测试至少覆盖：跨 chunk 行、跨 chunk UTF-8 内容、多行 data、缺少末尾空行、重复 event id、断线恢复、Abort、业务终态、401/403 和重试耗尽。

### 5.4 `AgentRunClient`

对页面提供“创建 Run、观察状态、取消、重试、恢复、读取最终详情”的连贯 interface。内部组合 HTTP 与 SSE，并实现有限状态机；页面不自行拼接事件数组。SSE 到达终态后必须重新读取 Run 详情，最终输出以详情响应为准。

### 5.5 `ShopAssistant`

这是消费者 AI 体验的稳定 seam。首个 production adapter 调用现有 `/api/shop-summary/**` 接口，未来 adapter 改为通用 Agent Run。页面只依赖提问、比较、推荐、流式状态和结果模型，不感知后端迁移。

legacy adapter 不是权限绕过手段。只有 bootstrap 表明当前用户拥有有效 scope 和 `AGENT_RUN` 时才能调用；无权限状态必须由模块明确返回并由页面正常处理。普通消费者是否应自动获得 Shop AI 访问权属于第 7.3 节的产品与安全决策，在该决策完成前不得自动创建 membership、冒用用户 `1` 或静默注入 `default` scope。

### 5.6 `ResponseBlockRenderer`

采用注册式渲染，支持 `TEXT`、`MARKDOWN`、`TABLE`、`CARD`、`CITATION`、`FILE`、`IMAGE`、`FORM`、`PROGRESS` 和 `WARNING`。未知类型必须安全降级为可检查的文本/JSON，不能白屏。

Markdown/HTML 必须净化；外链使用安全属性；文件和图片处理鉴权、加载失败、下载名称和对象 URL 释放；FORM 不得绕过 Zod 校验直接提交任意工具参数。

## 6. 路由与产品范围

### 6.1 公共与消费者端

建议路由：

```text
/login
/
/shops/:shopId
/community
/posts/:postId
/publish
/users/:userId
/account
/assistant
```

首期真实能力包括登录、店铺类型和发现、店铺详情、热门/关注内容流、内容详情、点赞、关注、发布、优惠券列表/秒杀、签到以及店铺 AI 问答/比较/推荐。具体字段和交互必须以 Controller 与 DTO 为准。

通用 `/api/v1/agent-runs` 当前要求 `AGENT_RUN` 权限，而普通买家没有可靠的可运行 Agent 枚举能力。消费者 AI 首期的技术适配路径是 `ShopAssistant` 的 legacy adapter，不要在页面中直接绑定通用 Run；但 legacy 接口同样要求 AI 权限和 membership，只有已授权用户才能看到并使用该能力。普通消费者的普遍开放必须先通过第 7.3 节门禁。

订单列表/详情和评论接口不完整，在后端契约补齐前不进入可完成范围。秒杀提交可以显示真实返回结果，但不得据此虚构完整订单中心。

### 6.2 商家端

建议路由：

```text
/merchant
/merchant/content
/merchant/vouchers
/merchant/shops/:shopId
```

商家壳层和权限守卫可以先建立。因为当前缺少“我的店铺”列表等闭环接口，导航只展示已经能够确定 shop scope 并真实完成的页面。不要用 URL 中任意 shopId 代替商家资源授权。

### 6.3 AI Studio 与运营端

建议路由：

```text
/studio/agents
/studio/agents/:agentId
/studio/runs
/studio/runs/:runId
/studio/prompts
/studio/models
/studio/knowledge
/studio/approvals
/studio/workflows
/studio/tools
/studio/evaluations
/studio/mcp
/studio/memory
/studio/operations
```

导航由 permissions 驱动，而不是由硬编码角色名称驱动。无权限路由应显示明确的 403 页面；资源不存在和跨 scope 访问不能混同为普通空状态。

Workflow DTO 当前不保存画布坐标。首版若开放只使用确定性自动布局；不得将 UI 坐标混入执行定义。需要持久化人工布局时，先设计独立 designer metadata 契约并记录 ADR。

## 7. 后端契约启用项

后端“已收束”不等于前端可以猜测契约。以下是允许在前端阶段进行的最小启用工作；实现必须沿用现有 application/domain 结构、权限检查和测试习惯，Controller 不承载业务逻辑。

### 7.1 P0：登录态 bootstrap

新增一个只要求有效登录、不要求预先提供 tenant/workspace 的 bootstrap 操作。推荐：

```http
GET /api/v1/session/bootstrap
Authorization: Bearer <token>
```

建议响应语义：

```json
{
  "user": { "id": "1", "nickName": "...", "icon": "..." },
  "memberships": [
    {
      "tenant": { "id": "default", "name": "Default" },
      "workspace": { "id": "default", "name": "Default" },
      "roles": ["ADMIN"],
      "permissions": ["AGENT_RUN", "AGENT_MANAGE"],
      "isDefault": true
    }
  ],
  "defaultScope": { "tenantId": "default", "workspaceId": "default" }
}
```

要求：

- 只返回当前用户真实 membership；
- 明确定义无 membership、默认 scope 失效和停用 membership 的响应；
- bootstrap 不能被现有 `AiPermissionInterceptor` 的“先提供 scope”规则形成循环依赖；
- permissions 按字符串传输，前端可识别已知值但必须容忍未来新增值；
- 增加未登录、无 membership、多 workspace 和停用 membership 测试。

### 7.2 P0：可运行 Agent 与 Run 历史

`GET /api/v1/agents` 当前要求 `AGENT_MANAGE`，不能作为 `AGENT_RUN` 用户的选择器。新增或调整一个只列出当前 scope 内已发布且用户可运行 Agent 的查询操作，至少返回 id、code、name、description 和 publishedVersion，并使用 `AGENT_RUN` 与资源 ACL 校验。

为 `/api/v1/agent-runs` 增加分页历史查询，支持最小的 status、agentId 和时间过滤。普通 `AGENT_RUN` 用户只能查看自己的 Run；具备 `RUN_INSPECT` 的用户才可以进入更广的观测范围。分页模型必须在 OpenAPI 中具体定义。

### 7.3 P0：消费者 Shop AI 访问策略

当前 `/api/shop-summary/**` 被 `AiPermissionInterceptor` 拦截，未提供 headers 时使用 `default` scope，但仍然要求该用户拥有有效 membership 和 `AGENT_RUN`。种子用户 `1` 的成功行为不能证明普通消费者可用。

进入消费者 AI 页面实施前，必须由用户确认以下产品策略之一：

1. 保持 workspace membership 模型：只有被授予 `AGENT_RUN` 的成员可使用 Shop AI，其他消费者不展示入口并获得明确的无权限状态；
2. 面向所有已登录消费者开放：先单独设计并批准受限的消费者执行身份、额度、审计、滥用防护和资源归属策略，再修改后端授权；不得通过自动加入管理 workspace、冒用种子用户或放宽通用 Agent 权限实现。

该选择会改变权限语义，不包含在默认前端实施授权中。未得到明确决定时，按第一种策略安全降级，并把“普通消费者普遍开放”标记为 `blocked`，但不阻塞其他消费者页面。

### 7.4 P0：OpenAPI 可消费化

补全首条垂直链路涉及的请求、响应、错误、分页、必需 headers 和 SSE event payload。不要用通用 `object` 或只引用无 schema 的 `Success` 代替真实 DTO。

记录旧 `Result<T>` 与 `/api/v1` 裸 DTO 的现实差异；不要为了统一文档而批量改变已经稳定的运行时响应。新接口采用明确、可验证的响应类型。

### 7.5 P1：按页面解锁的列表契约

对应页面开始前再逐项补充，不要一次扩张后端：

- Workflow 列表、版本列表；
- Tool 版本详情和发布；
- Knowledge 文档列表和版本列表；
- Evaluation 数据集、用例和运行列表；
- 商家“我的店铺”列表；
- 订单列表/详情；
- 评论增删查能力。

每项新增契约都必须有权限拒绝、tenant/workspace 隔离、分页边界和空结果测试，并同步 OpenAPI。若需要数据库 migration 或改变既有业务语义，停止该项并报告，不得自行扩大授权。

## 8. 状态、错误与交互规则

1. Query key 使用统一 factory，例如 `['agents', tenantId, workspaceId, filters]`；禁止遗漏 scope。
2. 列表筛选、分页和可分享的详情 tab 使用 URL search params；临时弹窗和折叠状态留在本地。
3. mutation 成功后只失效相关 scope 的精确查询；不要清空整个 QueryClient。
4. 搜索输入做防抖并取消旧请求，避免旧响应覆盖新结果。
5. 所有页面实现 loading、empty、error、forbidden、not-found 和 success 状态。背景刷新不得用整页骨架替换已有内容。
6. 401 触发一次受控 session 失效流程，禁止多个并发请求造成重复跳转；403 不自动退出登录。
7. mutation 错误保留表单输入。可重试失败提供明确重试操作，破坏性操作要求确认并防止重复提交。
8. Run 状态必须以服务器状态机为准；断线只表示观测中断，不等于 Run 失败。
9. 下载 artifact 必须经鉴权传输获取，不要把 token 拼入 URL。
10. 日志、toast、错误页和监控不得输出 token、resumeToken、完整 Prompt、完整模型响应或敏感文档内容。

## 9. 视觉、响应式与无障碍基线

消费者端可以更具内容感，但品牌、店铺和真实内容必须成为首屏信号。Merchant 和 Studio 应保持安静、紧凑、适合反复操作的工作台风格，不要做营销型大 Hero 或装饰性卡片堆叠。

必须遵守：

- 建立统一 design tokens，兼顾中性背景、清晰文字、状态色和品牌强调色；避免单一色系覆盖整个界面。
- 页面分区使用自然布局，不把每个 section 包成漂浮卡片，不做卡片套卡片。
- 工具操作优先使用 Lucide 图标并提供 tooltip；二元设置使用 toggle/checkbox，模式使用 segmented control，选项集使用 menu/select。
- 列表和表格为桌面密度优化；移动端转换为真正可读的行或详情布局，不能只靠横向溢出解决全部问题。
- 固定格式区域使用稳定的 grid、min/max、aspect-ratio 等约束，加载、hover 和动态内容不能导致明显跳动。
- 文本不得溢出按钮、侧栏、表格或卡片，不得与其他元素重叠。
- 完整支持键盘操作、可见焦点、语义标签、表单错误关联、对话框焦点圈定与恢复。
- 色彩对比至少达到 WCAG AA；状态不能只依赖颜色表达。
- 支持 `prefers-reduced-motion`，动画用于状态过渡而不是装饰。

每个主要阶段都使用 Playwright 在至少 360x800、768x1024 和 1440x900 视口截图检查。除了截图，还要检查 console error、网络失败、水平滚动、遮挡、空白区域和交互可达性。

## 10. 分阶段执行计划

### 阶段 0：契约与环境门禁

目标：确认真实后端契约，消除无法开始前端的 P0 阻塞。

交付：

- 契约台账；
- bootstrap、可运行 Agent、Run 历史的最终契约与后端测试；
- 消费者 Shop AI 访问策略的已批准决定，或明确记录为受权限限制的 deferred 能力；
- 首条垂直链路的具体 OpenAPI schema；
- 前端环境和包管理器选择记录。

门禁：相关 Maven 定向测试通过，OpenAPI 能描述实际请求和响应；前端不再需要硬编码默认 user/tenant/workspace/agent；Shop AI 不依赖种子用户或隐式权限假设。

### 阶段 1：前端基础设施

目标：创建可运行 SPA、三个壳层和共享深模块。

交付：

- Vite/React/TypeScript 工程、严格 TypeScript 配置、lint、format、unit、E2E 和 build scripts；
- `.env.example`，只包含非敏感 base URL 等配置；
- SessionScope、HttpTransport、StreamTransport、CanonicalError；
- 登录、bootstrap、scope selector、权限导航、401/403/404 页面；
- Consumer、Merchant、Studio 响应式布局；
- MSW 契约 fixtures，fixture 必须来自真实 DTO，不另造平行模型。

门禁：登录恢复与退出、无 membership、多 scope 切换、401/403、混合响应解析、旧缓存清理和 SSE transport 自动测试通过。

### 阶段 2：Agent Run 首条垂直闭环

目标：以最小但完整的业务链路验证整个架构。

链路：

```text
可运行 Agent 列表
  -> 创建 Run
  -> 连接 SSE 并展示生命周期
  -> 断线恢复/取消
  -> 终态重新获取 Run 详情
  -> ResponseBlockRenderer
  -> Run 检查与反馈（有权限时）
```

必须展示 queued/running/waiting/completed/failed/cancelled 等后端真实状态，并覆盖批准等待、重试、恢复和错误分支中实际可达的部分。不得生成假的 token 流。

门禁：真实后端或可重复的本地 provider stub 下完成 E2E；SSE headers、Last-Event-ID、终态详情刷新、未知 block、未知 fallbackReason 和 scope 隔离通过测试。

### 阶段 3：消费者端

按业务闭环实施：

1. 登录与账户；
2. 店铺类型、发现与详情；
3. 社区热门/关注流、内容详情、点赞、关注和发布；
4. 优惠券浏览与秒杀提交；
5. 对拥有有效 AI scope/permission 的用户提供 ShopAssistant 问答、比较、推荐和安全流式展示；
6. 签到和用户公开信息。

只有后端接口存在且权限清晰的页面才进入导航。上传文件要校验类型、大小、预览、失败重试和清理。

门禁：匿名访问、登录跳转、移动端主链路、空数据、弱网/错误、重复点击和 XSS payload 测试通过。

### 阶段 4：AI Studio

优先顺序：模型 -> Prompt -> Agent -> Knowledge -> Approval -> Run 观测。每种版本化资源统一表达 DRAFT、校验、发布、diff、rollback 语义，避免各页面分别实现一套状态规则。

Knowledge 覆盖上传、ingestion 状态、检索检查和删除确认。Approval 显示工具调用上下文，但不得泄露未授权参数或密钥。Run 详情按 overview、nodes、model calls、tool calls、retrievals、artifacts、usage 分区，并根据权限懒加载。

门禁：版本状态转换、权限导航、发布前校验、上传/轮询、敏感信息遮盖和跨 scope 拒绝通过测试。

### 阶段 5：商家与运营端

在“我的店铺”和必要列表接口真实可用后，实施商家店铺经营、内容、优惠券及允许的 AI 辅助能力。运营端接入 RBAC、店铺类型、缓存/索引维护等现有管理能力时，危险操作必须有二次确认、影响说明和结果审计展示。

门禁：商家只能操作被授权店铺；普通消费者不能进入 Merchant/Operations；危险 mutation 不可重复提交，403 不被伪装成空列表。

### 阶段 6：高级能力

在 P1 契约补齐后再开放 Workflow、Tool、Evaluation、MCP 和 Memory 的完整管理。

Workflow 首版采用确定性自动布局；若引入可编辑画布，必须覆盖节点选择、连线、校验错误定位、未保存离开提示、键盘可达性和大图性能。Monaco 与画布均按路由懒加载，不进入消费者 bundle。

门禁：每个高级模块必须有真实的列表 -> 详情/编辑 -> 校验 -> 发布/运行 -> 结果检查闭环，否则保持 feature-disabled，不展示半成品导航。

## 11. 测试策略

测试以深模块 interface 和用户可观察行为为表面，不要锁死 implementation 细节。

### 单元与模块测试

- SessionScope：登录恢复、token 失效、membership、默认 scope、scope 切换和并发失效；
- HttpTransport：裸 DTO、`Result<T>`、HTTP 200 业务失败、非 JSON、文件、Abort、headers 和错误归一化；
- StreamTransport：完整覆盖第 5.3 节；
- AgentRunClient：状态转换、终态详情、取消/恢复、断线与重复事件；
- ResponseBlockRenderer：所有已知块、未知块、XSS、无效字段和超大内容；
- Query key factory：tenant/workspace 隔离和精确失效。

### 集成测试

使用 MSW 模拟网络 seam，但 fixtures 必须与实际 DTO/OpenAPI 一致。覆盖路由守卫、权限导航、表单错误、列表分页、mutation 后缓存更新和 scope 切换。

### E2E

至少覆盖：

1. 登录 -> bootstrap -> 选择 workspace -> 登出；
2. Agent -> Run -> SSE -> 最终结果 -> 详情；
3. 已授权消费者店铺 -> AI 助手，以及未授权消费者的正确降级；
4. 社区浏览 -> 点赞/关注 -> 发布；
5. Studio 版本资源的创建/校验/发布；
6. 无权限用户访问受限路由；
7. scope A 数据不能出现在 scope B。

真实外部模型不可用时，可使用仓库已有本地 OpenAI-compatible stub 验证确定性链路，但必须明确标记“未验证真实 provider”。不得把外部测试跳过报告成通过。

## 12. 每阶段验证命令

根据最终 `package.json` 暴露等价命令，至少包含：

```text
npm run lint
npm run typecheck
npm run test
npm run build
npm run test:e2e
```

若修改了后端，额外执行与改动对应的定向测试，并在阶段收束时执行：

```text
mvn -q test
mvn -q spotless:check
mvn -q -DskipTests package
```

只有涉及容器适配、数据库、Redis、MinIO 或完整后端契约时才运行 `mvn -q -Pfull-integration verify`。记录每条命令的退出码、测试数量、跳过原因和关键日志位置。

实现完成后启动本地前端开发服务器。如果默认端口已占用，选择其他端口，并向用户提供可访问 URL。不要留下无法说明用途的后台进程。

## 13. 阻塞与停止条件

遇到以下情况时，停止对应模块并向用户报告，但继续推进不依赖它的工作：

- 需要改变 tenant/workspace、角色或资源 ACL 的业务语义；
- 需要新增或迁移数据库结构；
- 实际 DTO 与业务含义无法从代码、测试和运行行为确定；
- 缺少完成闭环所需的查询/详情接口，且不在本文允许的 P0/P1 启用项中；
- 外部服务、真实模型或凭据不可用；
- 用户现有改动与本任务发生不可安全合并的冲突。

报告必须区分 `completed`、`blocked`、`not verified` 和 `deferred`。禁止使用占位数据、吞错、放宽权限或删除测试来绕过阻塞。

## 14. 完成交付物

完成后必须交付：

1. `frontend/` 源码、测试、锁文件和非敏感环境示例；
2. 更新后的 `docs/api/openapi.yaml` 与契约台账；
3. 必要的前端架构说明和发生重大决策时的 ADR；
4. Playwright 桌面、平板、移动端截图及检查结论；
5. 每阶段验证命令、退出码、测试统计和未验证项；
6. 变更文件与用户可见行为摘要；
7. 按 P1/P2/P3 排序的剩余风险、阻塞项、前置条件和建议 owner；
8. 本地运行 URL 与复现关键链路的最短步骤。

## 15. 最终完成定义

只有同时满足以下条件，才能声明前端阶段完成：

- 三个壳层可运行，导航和路由由真实 session/scope/permission 驱动；
- 所有已开放页面均连接真实后端，无假数据完成项；
- HTTP、SSE、错误、缓存和输出块兼容逻辑集中在对应深模块中；
- tenant/workspace 隔离、401/403、XSS、敏感信息和危险操作防护有自动测试；
- 核心用户链路在桌面和移动端通过 Playwright，页面无明显溢出、遮挡或 console error；
- lint、typecheck、unit、build 和要求范围内的 E2E 全部通过；
- 跳过的外部验证、未补接口和高级模块均被明确记录，没有被描述为已经完成。

执行过程中以可验证的垂直闭环为进度单位。不要用页面数量代替完成度，也不要让传输、权限和兼容复杂度重新扩散到各个页面。
