-- Enterprise-oriented voucher seckill upgrade.

DROP PROCEDURE IF EXISTS `hmdp_assert_no_duplicate_voucher_orders`;
DELIMITER $$
CREATE PROCEDURE `hmdp_assert_no_duplicate_voucher_orders`()
BEGIN
    DECLARE duplicate_order_groups BIGINT DEFAULT 0;
    DECLARE duplicate_order_message VARCHAR(128);

    SELECT COUNT(*)
    INTO duplicate_order_groups
    FROM (
        SELECT `user_id`, `voucher_id`
        FROM `tb_voucher_order`
        GROUP BY `user_id`, `voucher_id`
        HAVING COUNT(*) > 1
    ) duplicate_orders;

    IF duplicate_order_groups > 0 THEN
        SET duplicate_order_message = CONCAT(
            'Duplicate voucher order groups: ',
            duplicate_order_groups,
            '; resolve manually before migration'
        );
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = duplicate_order_message;
    END IF;
END$$
DELIMITER ;

CALL `hmdp_assert_no_duplicate_voucher_orders`();
DROP PROCEDURE IF EXISTS `hmdp_assert_no_duplicate_voucher_orders`;

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
