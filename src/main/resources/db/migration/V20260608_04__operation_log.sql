-- Admin operation audit log.

CREATE TABLE IF NOT EXISTS `sys_operation_log` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `operator_user_id` bigint(20) UNSIGNED DEFAULT NULL COMMENT 'Operator user id',
  `module` varchar(64) NOT NULL COMMENT 'Business module',
  `operation` varchar(64) NOT NULL COMMENT 'Operation code',
  `target_type` varchar(64) DEFAULT NULL COMMENT 'Target type',
  `target_id` varchar(128) DEFAULT NULL COMMENT 'Target id',
  `detail` varchar(1000) DEFAULT NULL COMMENT 'Operation detail',
  `success` tinyint(1) NOT NULL DEFAULT 1 COMMENT '1 success, 0 fail',
  `fail_reason` varchar(255) DEFAULT NULL COMMENT 'Fail reason',
  `ip` varchar(64) DEFAULT NULL COMMENT 'Client IP',
  `user_agent` varchar(512) DEFAULT NULL COMMENT 'User-Agent',
  `operation_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Operation time',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_sys_operation_log_operator` (`operator_user_id`) USING BTREE,
  KEY `idx_sys_operation_log_module_time` (`module`, `operation_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Admin operation audit log';
