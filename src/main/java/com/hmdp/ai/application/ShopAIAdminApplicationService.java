package com.hmdp.ai.application;

import com.hmdp.ai.memory.MemoryService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

@Service
public class ShopAIAdminApplicationService {

    @Resource
    private MemoryService memoryService;

    public int cleanupMemoryByFunction(String functionType) {
        return memoryService.cleanupMemoryByFunction(functionType);
    }

    public Map<String, Map<String, Integer>> getMemoryStats() {
        return memoryService.getMemoryStats();
    }
}
