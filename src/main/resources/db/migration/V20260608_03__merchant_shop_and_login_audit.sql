-- Merchant shop ownership and login audit extension.

CREATE TABLE IF NOT EXISTS `sys_merchant_shop` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `merchant_user_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Merchant user id, references tb_user.id',
  `shop_id` bigint(20) UNSIGNED NOT NULL COMMENT 'Shop id, references tb_shop.id',
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '1 enabled, 0 disabled',
  `remark` varchar(255) DEFAULT NULL COMMENT 'Remark',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Create time',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_sys_merchant_shop_user_shop` (`merchant_user_id`, `shop_id`) USING BTREE,
  KEY `idx_sys_merchant_shop_shop_id` (`shop_id`) USING BTREE,
  KEY `idx_sys_merchant_shop_merchant_user_id` (`merchant_user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Merchant shop ownership';

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_login_log'
      AND column_name = 'action'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE sys_login_log ADD COLUMN action varchar(32) DEFAULT NULL COMMENT ''login/logout/register'' AFTER login_type',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
