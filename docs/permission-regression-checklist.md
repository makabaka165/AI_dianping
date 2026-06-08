# 权限回归测试清单

本文档用于 Sa-Token 迁移阶段回归验证。每次补注解、调整角色权限或准备删除旧拦截器前，都应至少跑一遍。

## 准备数据

1. 准备四类账号：游客、buyer、merchant、admin。
2. buyer 只绑定 `buyer` 角色。
3. merchant 只绑定 `merchant` 角色，并在 `sys_merchant_shop` 绑定至少一个自己的 `shop_id`。
4. admin 只绑定 `admin` 角色。
5. 准备一个不属于 merchant 的其他店铺，用于校验越权。

## 游客

| 场景 | 接口 | 期望 |
| --- | --- | --- |
| 浏览店铺 | `GET /shop/{id}` | 通过 |
| 浏览店铺类型 | `GET /shop-type/list` | 通过 |
| 浏览热门博客 | `GET /blog/hot` | 通过 |
| 关注用户 | `PUT /follow/{id}/true` | 拒绝，未登录 |
| 点赞博客 | `PUT /blog/like/{id}` | 拒绝，未登录 |
| 秒杀下单 | `POST /voucher-order/seckill/{id}` | 拒绝，未登录 |

## Buyer

| 场景 | 接口 | 期望 |
| --- | --- | --- |
| 查看当前用户 | `GET /user/me` | 通过 |
| 发布博客 | `POST /blog` | 通过 |
| 点赞博客 | `PUT /blog/like/{id}` | 通过 |
| 关注用户 | `PUT /follow/{id}/true` | 通过 |
| 秒杀下单 | `POST /voucher-order/seckill/{id}` | 通过 |
| 文档管理 | `GET /document/list` | 拒绝，权限不足 |
| AI 记忆统计 | `GET /api/shop-summary/memory/stats` | 拒绝，权限不足 |
| 后台角色分配 | `GET /admin/rbac/roles` | 拒绝，权限不足 |

## Merchant

| 场景 | 接口 | 期望 |
| --- | --- | --- |
| 更新自己的店铺 | `PUT /shop` | 通过 |
| 更新别人的店铺 | `PUT /shop` | 拒绝，返回无权操作非本人店铺 |
| 给自己的店铺创建普通券 | `POST /voucher` | 通过 |
| 给别人的店铺创建普通券 | `POST /voucher` | 拒绝 |
| 给自己的店铺创建秒杀券 | `POST /voucher/seckill` | 通过 |
| 文档管理 | `GET /document/list` | 拒绝，权限不足 |
| 后台角色分配 | `GET /admin/rbac/roles` | 拒绝，权限不足 |

## Admin

| 场景 | 接口 | 期望 |
| --- | --- | --- |
| 文档管理 | `GET /document/list` | 通过 |
| AI 记忆统计 | `GET /api/shop-summary/memory/stats` | 通过 |
| 查询全部角色 | `GET /admin/rbac/roles` | 通过 |
| 查询权限清单 | `GET /admin/rbac/permissions` | 通过 |
| 查询用户角色 | `GET /admin/rbac/users/{userId}/roles` | 通过 |
| 分配用户角色 | `POST /admin/rbac/users/roles` | 通过 |
| 创建店铺 | `POST /shop` | 通过 |
| 创建优惠券 | `POST /voucher` | 通过 |

## 审计日志

| 场景 | 期望 |
| --- | --- |
| 登录成功 | `sys_login_log` 写入 `action=login`、`success=1`、`user_id`、`phone`、`ip`、`user_agent`、`token_id` |
| 验证码错误 | `sys_login_log` 写入 `action=login`、`success=0`、`fail_reason=验证码错误` |
| 新用户自动注册 | `sys_login_log` 写入 `action=register` |
| 登出 | `sys_login_log` 写入 `action=logout`、`logout_time`、`token_id` |
