-- Align indexes required by current blog and voucher query paths.

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_blog'
      AND index_name = 'idx_blog_shop_active_time'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_blog ADD KEY idx_blog_shop_active_time (shop_id, status, deleted, create_time) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_blog'
      AND index_name = 'idx_blog_user_active_time'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_blog ADD KEY idx_blog_user_active_time (user_id, status, deleted, create_time) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_blog'
      AND index_name = 'idx_blog_active_liked_time'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_blog ADD KEY idx_blog_active_liked_time (status, deleted, liked, create_time) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
