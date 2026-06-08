-- RBAC base schema and seed data.

CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_key` varchar(64) NOT NULL COMMENT '角色标识',
  `role_name` varchar(64) NOT NULL COMMENT '角色名称',
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_sys_role_role_key` (`role_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

CREATE TABLE IF NOT EXISTS `sys_permission` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `permission_code` varchar(128) NOT NULL COMMENT '权限码',
  `permission_name` varchar(128) NOT NULL COMMENT '权限名称',
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_sys_permission_code` (`permission_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限表';

CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户ID，对应tb_user.id',
  `role_id` bigint(20) UNSIGNED NOT NULL COMMENT '角色ID',
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_sys_user_role_user_role` (`user_id`, `role_id`) USING BTREE,
  KEY `idx_sys_user_role_role_id` (`role_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

CREATE TABLE IF NOT EXISTS `sys_role_permission` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id` bigint(20) UNSIGNED NOT NULL COMMENT '角色ID',
  `permission_id` bigint(20) UNSIGNED NOT NULL COMMENT '权限ID',
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_sys_role_permission_role_perm` (`role_id`, `permission_id`) USING BTREE,
  KEY `idx_sys_role_permission_permission_id` (`permission_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

CREATE TABLE IF NOT EXISTS `sys_login_log` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED DEFAULT NULL COMMENT '用户ID',
  `phone` varchar(11) DEFAULT NULL COMMENT '手机号码',
  `login_type` varchar(32) DEFAULT NULL COMMENT '登录类型',
  `success` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否成功：1成功，0失败',
  `fail_reason` varchar(255) DEFAULT NULL COMMENT '失败原因',
  `ip` varchar(64) DEFAULT NULL COMMENT '登录IP',
  `user_agent` varchar(512) DEFAULT NULL COMMENT 'User-Agent',
  `token_id` varchar(128) DEFAULT NULL COMMENT '登录令牌ID',
  `login_time` timestamp NULL DEFAULT NULL COMMENT '登录时间',
  `logout_time` timestamp NULL DEFAULT NULL COMMENT '登出时间',
  `risk_level` tinyint(1) NOT NULL DEFAULT 0 COMMENT '风险等级',
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态：1有效，0无效',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_sys_login_log_user_id` (`user_id`) USING BTREE,
  KEY `idx_sys_login_log_phone` (`phone`) USING BTREE,
  KEY `idx_sys_login_log_login_time` (`login_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录审计表';

INSERT INTO `sys_role` (`role_key`, `role_name`, `status`, `remark`) VALUES
('buyer', '买家/普通用户', 1, '默认注册用户角色'),
('merchant', '商家', 1, '商家角色'),
('admin', '管理员', 1, '系统管理员')
ON DUPLICATE KEY UPDATE
  `role_name` = VALUES(`role_name`),
  `status` = VALUES(`status`),
  `remark` = VALUES(`remark`);

INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `status`, `remark`) VALUES
('shop:read', '查看店铺', 1, '基础浏览权限'),
('shop_type:read', '查看店铺分类', 1, '基础浏览权限'),
('blog:read', '查看笔记', 1, '基础浏览权限'),
('blog:create', '发布笔记', 1, '买家内容权限'),
('blog:like', '点赞笔记', 1, '买家互动权限'),
('follow:write', '关注/取关', 1, '买家社交权限'),
('voucher:read', '查看优惠券', 1, '优惠券浏览权限'),
('voucher:seckill', '秒杀优惠券', 1, '买家秒杀权限'),
('user:profile', '查看/维护个人资料', 1, '用户个人中心权限'),
('ai:chat', 'AI对话', 1, 'AI咨询权限'),
('shop:update:own', '更新自有店铺', 1, '商家店铺权限'),
('voucher:create:own', '创建自有店铺优惠券', 1, '商家优惠券权限'),
('voucher:update:own', '更新自有店铺优惠券', 1, '商家优惠券权限'),
('shop:data:read:own', '查看自有店铺数据', 1, '商家数据权限'),
('user:read', '查看用户', 1, '后台用户权限'),
('user:disable', '禁用用户', 1, '后台用户权限'),
('shop:create', '创建店铺', 1, '后台店铺权限'),
('shop:update', '更新店铺', 1, '后台店铺权限'),
('shop:delete', '删除店铺', 1, '后台店铺权限'),
('voucher:manage', '管理优惠券', 1, '后台优惠券权限'),
('blog:delete', '删除笔记', 1, '后台内容权限'),
('document:manage', '管理文档', 1, '后台文档权限'),
('ai:memory:manage', '管理AI记忆', 1, '后台AI权限'),
('system:log:read', '查看系统日志', 1, '后台日志权限'),
('role:assign', '分配角色', 1, '后台权限管理')
ON DUPLICATE KEY UPDATE
  `permission_name` = VALUES(`permission_name`),
  `status` = VALUES(`status`),
  `remark` = VALUES(`remark`);

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 1
FROM `sys_role` r
JOIN `sys_permission` p ON p.permission_code IN (
  'shop:read', 'shop_type:read', 'blog:read', 'blog:create', 'blog:like',
  'follow:write', 'voucher:read', 'voucher:seckill', 'user:profile', 'ai:chat'
)
WHERE r.role_key = 'buyer';

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 1
FROM `sys_role` r
JOIN `sys_permission` p ON p.permission_code IN (
  'shop:read', 'shop:update:own', 'voucher:read', 'voucher:create:own',
  'voucher:update:own', 'blog:read', 'shop:data:read:own', 'ai:chat'
)
WHERE r.role_key = 'merchant';

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 1
FROM `sys_role` r
JOIN `sys_permission` p ON p.permission_code IN (
  'user:read', 'user:disable', 'shop:create', 'shop:update', 'shop:delete',
  'voucher:manage', 'blog:delete', 'document:manage', 'ai:memory:manage',
  'system:log:read', 'role:assign'
)
WHERE r.role_key = 'admin';
