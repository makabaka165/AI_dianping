package com.hmdp.ai.application;

import com.hmdp.ai.memory.MemoryService;
import com.hmdp.utils.LocalCacheManager;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

@Service
public class ShopAIAdminApplicationService {

    @Resource
    private MemoryService memoryService;

    @Resource
    private LocalCacheManager localCacheManager;

    public int cleanupMemoryByFunction(String functionType) {
        return memoryService.cleanupMemoryByFunction(functionType);
    }

    public Map<String, Map<String, Integer>> getMemoryStats() {
        return memoryService.getMemoryStats();
    }

    public void clearToolCallCounters() {
        localCacheManager.clearToolCallCounters();
    }

    public void clearToolCallCounter(String sessionId, String toolName, Object... params) {
        localCacheManager.clearToolCallCounter(sessionId, toolName, params);
    }

    public void cleanupExpiredToolCallCounters() {
        localCacheManager.cleanupExpiredToolCallCounters();
    }
}
