package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.ShopSummaryService;
import com.hmdp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;
    
    @Resource
    public ShopSummaryService shopSummaryService;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        // 写入数据库
        Result result = shopService.update(shop);
        
        // 如果更新成功，更新相关的本地缓存
        if (result.getSuccess()) {
            // 更新店铺存在性缓存（设置为存在）
            shopSummaryService.updateShopExistsCache(shop.getId(), true);
            // 注意：评价数量缓存无法在此处更新，因为评价数据不在shop表中
            // 但在实际应用中，如果有评价数据的更新操作，也应该更新相应的缓存
        }
        
        return result;
    }
    
    /**
     * 获取店铺统计信息
     * @param id 店铺ID
     * @return 店铺统计信息
     */
    @GetMapping("/{id}/stats")
    public Result getShopStats(@PathVariable("id") Long id) {
        Map<String, Object> stats = new HashMap<>();
        
        // 使用本地缓存获取店铺存在性
        boolean exists = shopSummaryService.shopExists(id);
        stats.put("exists", exists);
        
        if (exists) {
            // 使用本地缓存获取店铺评价数量
            int reviewCount = shopSummaryService.getShopReviewCount(id);
            stats.put("reviewCount", reviewCount);
        }
        
        return Result.ok(stats);
    }
    
    /**
     * 获取店铺状态
     * @param id 店铺ID
     * @return 店铺状态
     */
    @GetMapping("/{id}/status")
    public Result getShopStatus(@PathVariable("id") Long id) {
        Map<String, Object> status = new HashMap<>();
        
        // 使用本地缓存获取店铺存在性
        boolean exists = shopSummaryService.shopExists(id);
        status.put("exists", exists);
        
        if (exists) {
            // 使用本地缓存获取店铺评价数量
            int reviewCount = shopSummaryService.getShopReviewCount(id);
            status.put("reviewCount", reviewCount);
        }
        
        return Result.ok(status);
    }
}