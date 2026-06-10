-- Enterprise-oriented blog module upgrade.

CREATE TABLE IF NOT EXISTS `tb_blog_like` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `blog_id` bigint(20) UNSIGNED NOT NULL COMMENT 'blog id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT 'user id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'liked time',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_blog_user` (`blog_id`, `user_id`) USING BTREE,
  KEY `idx_blog_like_user` (`user_id`, `create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='blog like relation';

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_blog'
      AND column_name = 'status'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE tb_blog ADD COLUMN status tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT ''status: 0 draft, 1 published, 2 rejected'' AFTER comments',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_blog'
      AND column_name = 'deleted'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE tb_blog ADD COLUMN deleted tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT ''logical delete flag'' AFTER status',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_blog'
      AND column_name = 'publish_time'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE tb_blog ADD COLUMN publish_time timestamp NULL DEFAULT NULL COMMENT ''publish time'' AFTER deleted',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `tb_blog`
SET `publish_time` = `create_time`
WHERE `publish_time` IS NULL;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_blog'
      AND index_name = 'idx_blog_liked_time'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_blog ADD KEY idx_blog_liked_time (liked, create_time) USING BTREE',
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
      AND index_name = 'idx_blog_user_time'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_blog ADD KEY idx_blog_user_time (user_id, create_time) USING BTREE',
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
      AND index_name = 'idx_blog_shop_time'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_blog ADD KEY idx_blog_shop_time (shop_id, create_time) USING BTREE',
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
      AND index_name = 'idx_blog_status_publish'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_blog ADD KEY idx_blog_status_publish (status, deleted, publish_time) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

DELETE f1
FROM `tb_follow` f1
JOIN `tb_follow` f2
  ON f1.user_id = f2.user_id
 AND f1.follow_user_id = f2.follow_user_id
 AND f1.id > f2.id;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_follow'
      AND index_name = 'uk_follow_user'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_follow ADD UNIQUE KEY uk_follow_user (user_id, follow_user_id) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_follow'
      AND index_name = 'idx_followed_user'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_follow ADD KEY idx_followed_user (follow_user_id) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `status`, `remark`) VALUES
('blog:repair', 'Repair blog consistency', 1, 'Admin blog cache and counter repair permission')
ON DUPLICATE KEY UPDATE
  `permission_name` = VALUES(`permission_name`),
  `status` = VALUES(`status`),
  `remark` = VALUES(`remark`);

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 1
FROM `sys_role` r
JOIN `sys_permission` p ON p.permission_code = 'blog:repair'
WHERE r.role_key = 'admin';
