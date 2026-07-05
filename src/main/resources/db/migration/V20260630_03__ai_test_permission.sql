-- Dev/test AI diagnostic endpoints require an explicit admin permission.

INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `status`, `remark`) VALUES
('ai:test', 'AI test diagnostics', 1, 'Access dev/test-only AI diagnostic endpoints')
ON DUPLICATE KEY UPDATE
  `permission_name` = VALUES(`permission_name`),
  `status` = VALUES(`status`),
  `remark` = VALUES(`remark`);

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 1
FROM `sys_role` r
JOIN `sys_permission` p ON p.permission_code = 'ai:test'
WHERE r.role_key = 'admin';
