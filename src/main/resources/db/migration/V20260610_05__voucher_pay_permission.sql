-- Allow buyers to pay voucher orders.

INSERT INTO `sys_permission` (`permission_code`, `permission_name`, `status`, `remark`) VALUES
('voucher:pay', '支付优惠券订单', 1, '买家支付优惠券订单权限')
ON DUPLICATE KEY UPDATE
  `permission_name` = VALUES(`permission_name`),
  `status` = VALUES(`status`),
  `remark` = VALUES(`remark`);

INSERT IGNORE INTO `sys_role_permission` (`role_id`, `permission_id`, `status`)
SELECT r.id, p.id, 1
FROM `sys_role` r
JOIN `sys_permission` p ON p.permission_code = 'voucher:pay'
WHERE r.role_key = 'buyer';
