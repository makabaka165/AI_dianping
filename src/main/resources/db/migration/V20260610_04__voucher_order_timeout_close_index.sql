-- Support scanning unpaid voucher orders that have exceeded the payment timeout.

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_voucher_order'
      AND index_name = 'idx_voucher_order_status_create'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_voucher_order ADD KEY idx_voucher_order_status_create (status, create_time) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
