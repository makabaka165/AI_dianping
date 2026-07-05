package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    default Result payVoucherOrder(Long orderId) {
        return payVoucherOrder(orderId, null);
    }

    Result payVoucherOrder(Long orderId, String payRequestId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    boolean closeUnpaidVoucherOrder(Long orderId);

    int closeExpiredUnpaidVoucherOrders(int limit);
}
