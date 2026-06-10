-- Admin permission for follow consistency repair.

INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `status`, `remark`) VALUES
('follow:repair', 'Repair follow consistency', 1, 'Admin follow cache and feed repair permission')
ON DUPLICATE KEY UPDATE
  `permission_name` = VALUES(`permission_name`),
  `status` = VALUES(`status`),
  `remark` = VALUES(`remark`);

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 1
FROM `sys_role` r
JOIN `sys_permission` p ON p.permission_code = 'follow:repair'
WHERE r.role_key = 'admin';
