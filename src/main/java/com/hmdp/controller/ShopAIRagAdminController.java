package com.hmdp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hmdp.dto.Result;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import com.hmdp.service.ShopReviewVectorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/api/shop-summary/admin/rag")
@Slf4j
public class ShopAIRagAdminController {

    @Resource
    private ShopReviewVectorIndexService shopReviewVectorIndexService;

    @PostMapping("/shops/{shopId}/rebuild")
    @SaCheckPermission("ai:rag:manage")
    public Result rebuildShop(@PathVariable Long shopId,
                              @RequestParam(value = "limit", required = false) Integer limit) {
        try {
            ShopRagRebuildResult result = shopReviewVectorIndexService.rebuildShop(shopId, limit);
            return Result.ok(result);
        } catch (RuntimeException e) {
            log.error("重建店铺评价 RAG 索引失败, shopId={}", shopId, e);
            return Result.fail("重建店铺评价 RAG 索引失败");
        }
    }

    @PostMapping("/rebuild")
    @SaCheckPermission("ai:rag:manage")
    public Result rebuildAll(@RequestParam(value = "shopLimit", required = false) Integer shopLimit,
                             @RequestParam(value = "perShopLimit", required = false) Integer perShopLimit) {
        try {
            ShopRagRebuildResult result = shopReviewVectorIndexService.rebuildAll(shopLimit, perShopLimit);
            return Result.ok(result);
        } catch (RuntimeException e) {
            log.error("全量重建评价 RAG 索引失败", e);
            return Result.fail("全量重建评价 RAG 索引失败");
        }
    }
}
