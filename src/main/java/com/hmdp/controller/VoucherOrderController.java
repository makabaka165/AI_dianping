package com.hmdp.controller;


import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderPayRequest;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    @SaCheckPermission("voucher:seckill")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @PostMapping("pay/{id}")
    @SaCheckPermission("voucher:pay")
    public Result payVoucherOrder(@PathVariable("id") Long orderId,
                                  @RequestBody(required = false) @Validated VoucherOrderPayRequest request) {
        return voucherOrderService.payVoucherOrder(orderId, request == null ? null : request.getPayRequestId());
    }
}
