package com.hmdp.ai.application;

import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIOrchestrator;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.workflow.request.ChatWorkflowRequest;
import com.hmdp.ai.workflow.request.CompareWorkflowRequest;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.ai.workflow.request.RecommendWorkflowRequest;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.config.AiRequestContext;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.entity.ShopSummaryResult;
import com.hmdp.utils.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class ShopAIApplicationService {

    @Resource
    private ShopAIOrchestrator orchestrator;

    @Resource
    private MemoryService memoryService;

    @Resource
    private ModelGateway modelGateway;

    @Resource
    private LocalCacheManager localCacheManager;

    public ShopAIResponse chat(String userId, String sessionId, String message, Long shopId, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, sessionId, sourceEndpoint);
        context.setMemoryId(memoryService.aiChatKey(userId, context.getSessionId()));
        try {
            setThreadContext(context);
            return orchestrator.chat(context, ChatWorkflowRequest.builder()
                    .message(message)
                    .shopId(shopId)
                    .build());
        } finally {
            AiRequestContext.clear();
        }
    }

    public Flux<String> chatStream(String userId, String sessionId, String message, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, sessionId, sourceEndpoint);
        context.setMemoryId(memoryService.aiChatKey(userId, context.getSessionId()));
        setThreadContext(context);
        try {
            return modelGateway.streamChat(context.getMemoryId(), message)
                    .doFinally(signalType -> AiRequestContext.clear());
        } catch (Exception e) {
            AiRequestContext.clear();
            log.error("流式智能对话失败, sessionId={}", context.getSessionId(), e);
            return Flux.just("对话失败，请稍后重试");
        }
    }

    public ShopSummaryResult summary(String userId, Long shopId, boolean writeMemory, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, "summary_" + shopId, sourceEndpoint);
        if (writeMemory) {
            context.setMemoryId(memoryService.shopSummaryKey(shopId, userId));
        }
        try {
            setThreadContext(context);
            return orchestrator.summary(context, SummaryWorkflowRequest.builder()
                    .shopId(shopId)
                    .writeMemory(writeMemory)
                    .build());
        } finally {
            AiRequestContext.clear();
        }
    }

    public ShopAIResponse ask(String userId, String sessionId, Long shopId, String question, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, sessionId, sourceEndpoint);
        context.setMemoryId(memoryService.shopQAKey(shopId, userId));
        try {
            setThreadContext(context);
            return orchestrator.ask(context, QAWorkflowRequest.builder()
                    .shopId(shopId)
                    .question(question)
                    .build());
        } finally {
            AiRequestContext.clear();
        }
    }

    public ShopAIResponse compare(String userId, String sessionId, Long shopId1, Long shopId2, String aspect, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, sessionId, sourceEndpoint);
        context.setMemoryId(memoryService.shopCompareKey(userId, context.getSessionId()));
        try {
            setThreadContext(context);
            return orchestrator.compare(context, CompareWorkflowRequest.builder()
                    .shopId1(shopId1)
                    .shopId2(shopId2)
                    .aspect(aspect)
                    .build());
        } finally {
            AiRequestContext.clear();
        }
    }

    public ShopAIResponse recommend(String userId, String sessionId, String userPreference, String category, Integer limit, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, sessionId, sourceEndpoint);
        context.setMemoryId(memoryService.shopRecommendKey(userId));
        try {
            setThreadContext(context);
            return orchestrator.recommend(context, RecommendWorkflowRequest.builder()
                    .userPreference(userPreference)
                    .category(category)
                    .limit(limit)
                    .build());
        } finally {
            AiRequestContext.clear();
        }
    }

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

    public int cleanupMemoryByFunction(String functionType) {
        return memoryService.cleanupMemoryByFunction(functionType);
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

    public Map<String, Map<String, Integer>> getMemoryStats() {
        return memoryService.getMemoryStats();
    }

    public String shopSummaryMemoryKey(Long shopId, String userId) {
        return memoryService.shopSummaryKey(shopId, userId);
    }

    public String shopQAMemoryKey(Long shopId, String userId) {
        return memoryService.shopQAKey(shopId, userId);
    }

    public String shopRecommendMemoryKey(String userId) {
        return memoryService.shopRecommendKey(userId);
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

    private ShopAIRequestContext baseContext(String userId, String sessionId, String sourceEndpoint) {
        return ShopAIRequestContext.builder()
                .userId(userId)
                .sessionId(normalizeSessionId(sessionId))
                .traceId(newTraceId())
                .sourceEndpoint(sourceEndpoint)
                .build();
    }

    private void setThreadContext(ShopAIRequestContext context) {
        AiRequestContext.set(AiRequestContext.Context.builder()
                .userId(context.getUserId())
                .sessionId(context.getSessionId())
                .memoryId(context.getMemoryId())
                .traceId(context.getTraceId())
                .sourceEndpoint(context.getSourceEndpoint())
                .build());
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return "default";
        }
        return sessionId.trim();
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
