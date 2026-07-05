-- Allow re-seckill after unpaid orders are canceled while keeping paid/active orders unique.

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_voucher_order'
      AND column_name = 'pay_request_id'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE tb_voucher_order ADD COLUMN pay_request_id varchar(64) DEFAULT NULL COMMENT ''Mock payment idempotency key'' AFTER status',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_voucher_order'
      AND column_name = 'active_order_key'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE tb_voucher_order ADD COLUMN active_order_key tinyint(1) DEFAULT 1 COMMENT ''1 for active order; NULL releases re-seckill eligibility'' AFTER pay_request_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `tb_voucher_order`
SET `active_order_key` = NULL
WHERE `status` IN (4, 6);

UPDATE `tb_voucher_order`
SET `active_order_key` = 1
WHERE `status` NOT IN (4, 6)
  AND `active_order_key` IS NULL;

ALTER TABLE `tb_voucher_order`
    MODIFY COLUMN `active_order_key` tinyint(1) DEFAULT 1 COMMENT '1 for active order; NULL releases re-seckill eligibility';

DROP PROCEDURE IF EXISTS `hmdp_assert_no_duplicate_active_voucher_orders`;
DELIMITER $$
CREATE PROCEDURE `hmdp_assert_no_duplicate_active_voucher_orders`()
BEGIN
    DECLARE duplicate_active_order_groups BIGINT DEFAULT 0;
    DECLARE duplicate_active_order_message VARCHAR(128);

    SELECT COUNT(*)
    INTO duplicate_active_order_groups
    FROM (
        SELECT `user_id`, `voucher_id`
        FROM `tb_voucher_order`
        WHERE `active_order_key` = 1
        GROUP BY `user_id`, `voucher_id`
        HAVING COUNT(*) > 1
    ) duplicate_active_orders;

    IF duplicate_active_order_groups > 0 THEN
        SET duplicate_active_order_message = CONCAT(
            'Duplicate active voucher order groups: ',
            duplicate_active_order_groups,
            '; resolve manually before migration'
        );
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = duplicate_active_order_message;
    END IF;
END$$
DELIMITER ;

CALL `hmdp_assert_no_duplicate_active_voucher_orders`();
DROP PROCEDURE IF EXISTS `hmdp_assert_no_duplicate_active_voucher_orders`;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tb_voucher_order'
      AND index_name = 'uk_voucher_order_user_voucher'
);
SET @sql := IF(@idx_exists > 0,
    'ALTER TABLE tb_voucher_order DROP INDEX uk_voucher_order_user_voucher',
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
      AND index_name = 'uk_voucher_order_user_voucher_active'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_voucher_order_user_voucher_active (user_id, voucher_id, active_order_key) USING BTREE',
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
      AND index_name = 'idx_voucher_order_pay_request'
);
SET @sql := IF(@idx_exists = 0,
    'ALTER TABLE tb_voucher_order ADD KEY idx_voucher_order_pay_request (pay_request_id) USING BTREE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
