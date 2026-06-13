package com.hmdp.ai.application;

import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.orchestration.ShopAIOrchestrator;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.workflow.request.ChatWorkflowRequest;
import com.hmdp.ai.workflow.request.CompareWorkflowRequest;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.ai.workflow.request.QualitySummaryWorkflowRequest;
import com.hmdp.ai.workflow.request.RecommendWorkflowRequest;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAIStreamEvent;
import com.hmdp.entity.ShopSummaryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.util.UUID;

@Service
@Slf4j
public class ShopAIApplicationService {

    @Resource
    private ShopAIOrchestrator orchestrator;

    @Resource
    private MemoryService memoryService;

    public ShopAIResponse chat(String userId, String sessionId, String message, Long shopId, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, sessionId, sourceEndpoint);
        context.setMemoryId(memoryService.aiChatKey(userId, context.getSessionId()));
        return orchestrator.chat(context, ChatWorkflowRequest.builder()
                .message(message)
                .shopId(shopId)
                .build());
    }

    public Flux<ServerSentEvent<ShopAIStreamEvent>> chatStream(String userId, String sessionId, String message, Long shopId, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, sessionId, sourceEndpoint);
        context.setMemoryId(memoryService.aiChatKey(userId, context.getSessionId()));
        return orchestrator.chatStream(context, ChatWorkflowRequest.builder()
                        .message(message)
                        .shopId(shopId)
                        .build())
                .onErrorResume(e -> {
                    log.error("流式智能对话失败, sessionId={}", context.getSessionId(), e);
                    return Flux.just(ServerSentEvent.<ShopAIStreamEvent>builder()
                            .event("error")
                            .data(ShopAIStreamEvent.builder()
                                    .type("error")
                                    .traceId(context.getTraceId())
                                    .sessionId(context.getSessionId())
                                    .memoryId(context.getMemoryId())
                                    .message("对话失败，请稍后重试")
                                    .degraded(true)
                                    .build())
                            .build());
                });
    }

    public ShopSummaryResult summary(String userId, Long shopId, boolean writeMemory, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, "summary_" + shopId, sourceEndpoint);
        context.setMemoryId(memoryService.shopSummaryKey(shopId, userId));
        return orchestrator.summary(context, SummaryWorkflowRequest.builder()
                .shopId(shopId)
                .writeMemory(writeMemory)
                .build());
    }

    public ShopSummaryResult qualitySummary(String userId, Long shopId, Integer minLiked, Integer limit, boolean writeMemory, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, "quality_summary_" + shopId, sourceEndpoint);
        context.setMemoryId(memoryService.shopSummaryKey(shopId, userId));
        return orchestrator.qualitySummary(context, QualitySummaryWorkflowRequest.builder()
                .shopId(shopId)
                .minLiked(minLiked)
                .limit(limit)
                .writeMemory(writeMemory)
                .build());
    }

    public ShopAIResponse ask(String userId, String sessionId, Long shopId, String question, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, sessionId, sourceEndpoint);
        context.setMemoryId(memoryService.shopQAKey(shopId, userId));
        return orchestrator.ask(context, QAWorkflowRequest.builder()
                .shopId(shopId)
                .question(question)
                .build());
    }

    public ShopAIResponse compare(String userId, String sessionId, Long shopId1, Long shopId2, String aspect, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, sessionId, sourceEndpoint);
        context.setMemoryId(memoryService.shopCompareKey(userId, context.getSessionId()));
        return orchestrator.compare(context, CompareWorkflowRequest.builder()
                .shopId1(shopId1)
                .shopId2(shopId2)
                .aspect(aspect)
                .build());
    }

    public ShopAIResponse recommend(String userId, String sessionId, String userPreference, String category, Integer limit, String sourceEndpoint) {
        ShopAIRequestContext context = baseContext(userId, sessionId, sourceEndpoint);
        context.setMemoryId(memoryService.shopRecommendKey(userId));
        return orchestrator.recommend(context, RecommendWorkflowRequest.builder()
                .userPreference(userPreference)
                .category(category)
                .limit(limit)
                .build());
    }

    private ShopAIRequestContext baseContext(String userId, String sessionId, String sourceEndpoint) {
        return ShopAIRequestContext.builder()
                .userId(userId)
                .sessionId(normalizeSessionId(sessionId))
                .traceId(newTraceId())
                .sourceEndpoint(sourceEndpoint)
                .build();
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
