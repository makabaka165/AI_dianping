-- Ensure phone numbers are unique for idempotent user creation.
-- Run the duplicate check first on an existing database:
-- SELECT phone, COUNT(*) FROM tb_user GROUP BY phone HAVING COUNT(*) > 1;

SET @old_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_user'
      AND index_name = 'uniqe_key_phone'
);
SET @sql := IF(@old_index_exists > 0,
    'ALTER TABLE tb_user DROP INDEX uniqe_key_phone',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @new_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_user'
      AND index_name = 'unique_key_phone'
);
SET @sql := IF(@new_index_exists = 0,
    'ALTER TABLE tb_user ADD UNIQUE INDEX unique_key_phone (phone)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
