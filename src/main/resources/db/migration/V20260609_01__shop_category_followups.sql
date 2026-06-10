-- Shop and category enterprise follow-up changes.

ALTER TABLE `tb_shop`
  ADD COLUMN `version` int NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version' AFTER `open_hours`;

ALTER TABLE `tb_shop_type`
  ADD COLUMN `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '1 enabled, 0 disabled' AFTER `sort`,
  ADD KEY `idx_tb_shop_type_status_sort` (`status`, `sort`) USING BTREE;

INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `status`, `remark`) VALUES
('shop:create:own', 'Create own shop', 1, 'Merchant shop permission'),
('shop:type:manage', 'Manage shop types', 1, 'Admin shop type permission'),
('shop:geo:rebuild', 'Rebuild shop GEO index', 1, 'Admin shop GEO permission')
ON DUPLICATE KEY UPDATE
  `permission_name` = VALUES(`permission_name`),
  `status` = VALUES(`status`),
  `remark` = VALUES(`remark`);

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 1
FROM `sys_role` r
JOIN `sys_permission` p ON p.permission_code IN ('shop:create:own')
WHERE r.role_key = 'merchant';

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 1
FROM `sys_role` r
JOIN `sys_permission` p ON p.permission_code IN ('shop:create:own', 'shop:type:manage', 'shop:geo:rebuild')
WHERE r.role_key = 'admin';
