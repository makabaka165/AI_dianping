package com.hmdp.ai.application;

import com.hmdp.ai.memory.MemoryService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

@Service
public class ShopAIMemoryApplicationService {

    @Resource
    private MemoryService memoryService;

    public void clearShopQAMemory(String userId, Long shopId) {
        memoryService.clearShopQAMemory(userId, shopId);
    }

    public void clearShopSummaryMemory(String userId, Long shopId) {
        memoryService.clearShopSummaryMemory(userId, shopId);
    }

    public void clearRecommendMemory(String userId) {
        memoryService.clearRecommendMemory(userId);
    }

    public Map<String, Integer> clearAllUserMemory(String userId) {
        return memoryService.clearAllUserMemory(userId);
    }

    public boolean hasMemory(String memoryKey) {
        return memoryService.hasMemory(memoryKey);
    }

    public int getMemoryMessageCount(String memoryKey) {
        return memoryService.getMemoryMessageCount(memoryKey);
    }

    public long getMemoryTtl(String memoryKey) {
        return memoryService.getMemoryTtl(memoryKey);
    }

    public void refreshMemoryTtl(String memoryKey) {
        memoryService.refreshMemoryTtl(memoryKey);
    }
}
