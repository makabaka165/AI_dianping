-- Shop and category enterprise follow-up changes.

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_shop'
      AND column_name = 'version'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE tb_shop ADD COLUMN version int NOT NULL DEFAULT 0 COMMENT ''Optimistic lock version'' AFTER open_hours',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_shop_type'
      AND column_name = 'status'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE tb_shop_type ADD COLUMN status tinyint(1) NOT NULL DEFAULT 1 COMMENT ''1 enabled, 0 disabled'' AFTER sort',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_shop_type'
      AND index_name = 'idx_tb_shop_type_status_sort'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_shop_type ADD KEY idx_tb_shop_type_status_sort (status, sort) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

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
