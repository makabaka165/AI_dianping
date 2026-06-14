package com.hmdp.ai.application;

import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.infra.AiResultCacheService;
import com.hmdp.utils.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
@Slf4j
public class ShopAICacheInvalidationService {

    @Resource
    private LocalCacheManager localCacheManager;

    @Resource
    private AiResultCacheService aiResultCacheService;

    @Resource
    private MemoryService memoryService;

    public void clearShopRelatedCaches(Long shopId) {
        if (shopId == null || shopId <= 0) {
            return;
        }
        log.info("清除店铺{}相关AI缓存", shopId);
        localCacheManager.removeShopRelatedCaches(shopId);
        aiResultCacheService.evictShop(shopId);
        memoryService.clearAllShopSummaryMemory(shopId);
    }
}
