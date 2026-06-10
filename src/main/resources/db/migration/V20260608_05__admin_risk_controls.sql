-- Admin user controls and login risk audit fields.

ALTER TABLE `tb_user`
  ADD COLUMN `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '1 enabled, 0 disabled' AFTER `icon`;

ALTER TABLE `sys_login_log`
  ADD COLUMN `device_fingerprint` varchar(128) DEFAULT NULL COMMENT 'Device fingerprint' AFTER `user_agent`,
  ADD COLUMN `fail_count` int NOT NULL DEFAULT 0 COMMENT 'Failure count in risk window' AFTER `risk_level`,
  ADD KEY `idx_sys_login_log_device` (`device_fingerprint`) USING BTREE;
