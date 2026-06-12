INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `status`, `remark`) VALUES
('ai:rag:manage', '管理AI评价RAG索引', 1, '后台AI权限')
ON DUPLICATE KEY UPDATE
  `permission_name` = VALUES(`permission_name`),
  `status` = VALUES(`status`),
  `remark` = VALUES(`remark`);

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 1
FROM `sys_role` r
JOIN `sys_permission` p ON p.permission_code = 'ai:rag:manage'
WHERE r.role_key = 'admin';
