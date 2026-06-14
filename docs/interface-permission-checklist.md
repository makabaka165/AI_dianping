# 接口权限清单

本文档用于 Sa-Token 双轨迁移阶段的接口权限梳理。旧 `LoginInterceptor`、`RefreshTokenInterceptor` 暂时保留，下面的注解用于逐步替换旧保护逻辑。

## 公开接口

这些接口面向游客或前台浏览，不要求登录。

| Controller | 接口 | 说明 |
| --- | --- | --- |
| `UserController` | `POST /user/code` | 发送验证码 |
| `UserController` | `POST /user/login` | 登录 |
| `UserController` | `GET /user/{id}` | 查询用户基础信息 |
| `UserController` | `GET /user/info/{id}` | 查询用户详情 |
| `ShopController` | `GET /shop/{id}` | 店铺详情 |
| `ShopController` | `GET /shop/{id}/stats` | 店铺统计概览 |
| `ShopController` | `GET /shop/{id}/status` | 店铺状态概览 |
| `ShopTypeController` | `GET /shop-type/list` | 店铺类型 |
| `VoucherController` | `GET /voucher/list/{shopId}` | 店铺优惠券列表 |
| `BlogController` | `GET /blog/hot` | 热门博客 |
| `BlogController` | `GET /blog/{id}` | 博客详情 |
| `BlogController` | `GET /blog/likes/{id}` | 博客点赞榜 |
| `BlogController` | `GET /blog/of/user?id={id}` | 指定用户博客 |
| `AITestController` | `/api/ai/test/**` | 开发测试接口，生产应关闭或改 admin |

## 登录接口

这些接口只要求用户已登录，暂不区分角色。

| Controller | 接口 | 当前注解 |
| --- | --- | --- |
| `UserController` | `GET /user/me` | `@SaCheckLogin` |
| `UserController` | `POST /user/logout` | `@SaCheckLogin` |
| `UserController` | `POST /user/sign` | `@SaCheckLogin` |
| `UserController` | `GET /user/sign/count` | `@SaCheckLogin` |
| `BlogController` | `GET /blog/of/me` | `@SaCheckLogin` |
| `BlogController` | `GET /blog/of/follow` | `@SaCheckLogin` |
| `FollowController` | `GET /follow/or/not/{id}` | `@SaCheckLogin` |
| `FollowController` | `GET /follow/common/{id}` | `@SaCheckLogin` |
| `UploadController` | `POST /upload/blog` | `@SaCheckLogin` |
| `UploadController` | `DELETE /upload/blog` | `@SaCheckLogin` |

## 权限接口

这些接口需要明确业务权限码。

| Controller | 接口 | 当前注解 |
| --- | --- | --- |
| `BlogController` | `POST /blog` | `@SaCheckPermission("blog:create")` |
| `BlogController` | `PUT /blog/like/{id}` | `@SaCheckPermission("blog:like")` |
| `FollowController` | `PUT /follow/{id}/{isFollow}` | `@SaCheckPermission("follow:write")` |
| `VoucherOrderController` | `POST /voucher-order/seckill/{id}` | `@SaCheckPermission("voucher:seckill")` |
| `ShopController` | `PUT /shop` | `@SaCheckPermission("shop:update:own")` |
| `VoucherController` | `POST /voucher` | `voucher:create:own OR voucher:manage` |
| `VoucherController` | `POST /voucher/seckill` | `voucher:create:own OR voucher:manage` |
| `ShopSummaryController` | `/api/shop-summary/ai/**` | `@SaCheckPermission("ai:chat")` |
| `ShopSummaryController` | `GET /api/shop-summary/{shopId}` | `@SaCheckPermission("ai:chat")`，计配额 |
| `ShopSummaryController` | `POST /api/shop-summary/{shopId}/with-memory` | `@SaCheckPermission("ai:chat")` |
| `ShopSummaryController` | `GET /api/shop-summary/{shopId}/quality` | `@SaCheckPermission("ai:chat")`，无写记忆副作用 |
| `ShopSummaryController` | `POST /api/shop-summary/{shopId}/quality/with-memory` | `@SaCheckPermission("ai:chat")`，显式写 summary memory |
| `ShopSummaryController` | `POST /api/shop-summary/{shopId}/ask` | `@SaCheckPermission("ai:chat")` |
| `ShopSummaryController` | `POST /api/shop-summary/compare` | `@SaCheckPermission("ai:chat")` |
| `ShopSummaryController` | `POST /api/shop-summary/recommend` | `@SaCheckPermission("ai:chat")` |
| `ShopSummaryController` | `DELETE /api/shop-summary/{shopId}/memory/qa` | `@SaCheckPermission("ai:chat")`，用户自助 AI 记忆管理 |
| `ShopSummaryController` | `DELETE /api/shop-summary/{shopId}/memory/summary` | `@SaCheckPermission("ai:chat")`，用户自助 AI 记忆管理 |
| `ShopSummaryController` | `DELETE /api/shop-summary/memory/recommend` | `@SaCheckPermission("ai:chat")`，用户自助 AI 记忆管理 |
| `ShopSummaryController` | `DELETE /api/shop-summary/memory/all` | `@SaCheckPermission("ai:chat")`，用户自助 AI 记忆管理 |
| `ShopSummaryController` | `GET /api/shop-summary/memory/{shopId}/status` | `@SaCheckPermission("ai:chat")`，用户自助 AI 记忆状态 |
| `ShopSummaryController` | `POST /api/shop-summary/memory/{shopId}/refresh` | `@SaCheckPermission("ai:chat")`，用户自助刷新 AI 记忆 TTL |

## 管理员接口

这些接口属于后台管理能力，建议长期只给 admin 或专门的后台角色开放。

| Controller | 接口 | 当前注解 |
| --- | --- | --- |
| `ShopController` | `POST /shop` | `@SaCheckPermission("shop:create")` |
| `DocumentManagementController` | `/document/**` | `@SaCheckPermission("document:manage")` |
| `ShopSummaryController` | `GET /api/shop-summary/memory/stats` | `@SaCheckPermission("ai:memory:manage")` |
| `ShopSummaryController` | `DELETE /api/shop-summary/admin/memory/{functionType}` | `@SaCheckPermission("ai:memory:manage")` |
| `ShopAIRagAdminController` | `POST /api/shop-summary/admin/rag/shops/{shopId}/rebuild` | `@SaCheckPermission("ai:rag:manage")` |
| `ShopAIRagAdminController` | `POST /api/shop-summary/admin/rag/shops/{shopId}/compact` | `@SaCheckPermission("ai:rag:manage")`，只做重建刷新，不声明物理删除旧向量 |
| `ShopAIRagAdminController` | `POST /api/shop-summary/admin/rag/rebuild` | `@SaCheckPermission("ai:rag:manage")` |
| 待补 | 用户禁用 | `user:disable` |
| 待补 | 角色分配 | `role:assign` |
| 待补 | 系统日志 | `system:log:read` |

## 迁移顺序

1. 保留旧拦截器，优先给依赖 `UserHolder` 的接口补 `@SaCheckLogin`。
2. 给写接口补 `@SaCheckPermission`，权限码优先复用 RBAC 初始化数据。
3. 新业务代码使用 `CurrentUserService` 获取当前用户，减少直接依赖 `UserHolder`。
4. 登录审计接入 `sys_login_log`，记录登录成功、失败、登出。
5. 大部分接口都由 Sa-Token 注解覆盖后，再移除旧 Redis token 写入、`RefreshTokenInterceptor`、`LoginInterceptor`。
