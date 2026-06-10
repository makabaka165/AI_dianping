-- Enterprise-oriented voucher seckill upgrade.

DELETE o1
FROM `tb_voucher_order` o1
JOIN `tb_voucher_order` o2
  ON o1.user_id = o2.user_id
 AND o1.voucher_id = o2.voucher_id
 AND o1.id > o2.id;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_voucher_order'
      AND index_name = 'uk_voucher_order_user_voucher'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_voucher_order_user_voucher (user_id, voucher_id) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_voucher_order'
      AND index_name = 'idx_voucher_order_voucher'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_voucher_order ADD KEY idx_voucher_order_voucher (voucher_id, create_time) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
