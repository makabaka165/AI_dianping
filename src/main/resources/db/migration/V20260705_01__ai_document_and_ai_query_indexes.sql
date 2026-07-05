CREATE TABLE IF NOT EXISTS `tb_ai_document` (
  `id` varchar(64) NOT NULL,
  `title` varchar(255) NOT NULL,
  `source` varchar(255) DEFAULT NULL,
  `file_type` varchar(32) NOT NULL DEFAULT 'txt',
  `status` varchar(32) NOT NULL DEFAULT 'PUBLISHED',
  `quality_score` double DEFAULT 0,
  `quality_profile` varchar(64) DEFAULT NULL,
  `quality_level` varchar(64) DEFAULT NULL,
  `word_count` bigint DEFAULT 0,
  `keywords` varchar(512) DEFAULT NULL,
  `content` mediumtext,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_document_status_updated` (`status`, `updated_at`),
  KEY `idx_ai_document_quality` (`quality_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI RAG document metadata and content';

SET @idx_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'tb_blog'
    AND index_name = 'idx_blog_shop_liked_time'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE tb_blog ADD KEY idx_blog_shop_liked_time (shop_id, liked, create_time) USING BTREE',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'tb_follow'
    AND index_name = 'idx_follow_user_follow'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE tb_follow ADD KEY idx_follow_user_follow (user_id, follow_user_id) USING BTREE',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'tb_shop'
    AND index_name = 'idx_shop_type_score'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE tb_shop ADD KEY idx_shop_type_score (type_id, score) USING BTREE',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
