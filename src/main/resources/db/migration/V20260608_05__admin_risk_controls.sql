-- Admin user controls and login risk audit fields.

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_user'
      AND column_name = 'status'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE tb_user ADD COLUMN status tinyint(1) NOT NULL DEFAULT 1 COMMENT ''1 enabled, 0 disabled'' AFTER icon',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_login_log'
      AND column_name = 'device_fingerprint'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE sys_login_log ADD COLUMN device_fingerprint varchar(128) DEFAULT NULL COMMENT ''Device fingerprint'' AFTER user_agent',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_login_log'
      AND column_name = 'fail_count'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE sys_login_log ADD COLUMN fail_count int NOT NULL DEFAULT 0 COMMENT ''Failure count in risk window'' AFTER risk_level',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_login_log'
      AND index_name = 'idx_sys_login_log_device'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE sys_login_log ADD KEY idx_sys_login_log_device (device_fingerprint) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
