package com.hmdp.controller;


import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherCreateDTO;
import com.hmdp.dto.VoucherCreateDTO;
import com.hmdp.service.IVoucherService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增秒杀券
     * @param request 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    @SaCheckPermission(value = {"voucher:create:own", "voucher:manage"}, mode = SaMode.OR)
    public Result addSeckillVoucher(@RequestBody @Validated SeckillVoucherCreateDTO request) {
        return voucherService.addSeckillVoucher(request);
    }

    /**
     * 新增普通券
     * @param request 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    @SaCheckPermission(value = {"voucher:create:own", "voucher:manage"}, mode = SaMode.OR)
    public Result addVoucher(@RequestBody @Validated VoucherCreateDTO request) {
        return voucherService.addVoucher(request);
    }


    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
