package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.intent.IntentRouter;
import com.hmdp.ai.intent.IntentRoutingResult;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.ChatWorkflowRequest;
import com.hmdp.ai.workflow.request.CompareWorkflowRequest;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.ai.workflow.request.RecommendWorkflowRequest;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.entity.ShopSummaryResult;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;

@Component
public class ChatWorkflow implements ShopAIWorkflow<ChatWorkflowRequest, ShopAIResponse> {

    @Resource
    private IntentRouter intentRouter;

    @Resource
    private SummaryWorkflow summaryWorkflow;

    @Resource
    private QAWorkflow qaWorkflow;

    @Resource
    private CompareWorkflow compareWorkflow;

    @Resource
    private RecommendWorkflow recommendWorkflow;

    @Resource
    private MemoryService memoryService;

    @Resource
    private PromptTemplateRegistry promptTemplateRegistry;

    @Resource
    private ModelGateway modelGateway;

    @Resource
    private QualityGuard qualityGuard;

    @Resource
    private FallbackPolicy fallbackPolicy;

    @Override
    public ShopAIIntent intent() {
        return ShopAIIntent.FREE_CHAT;
    }

    @Override
    public ShopAIResponse execute(ShopAIRequestContext context, ChatWorkflowRequest request) {
        if (request == null || isBlank(request.getMessage())) {
            throw new IllegalArgumentException("消息不能为空");
        }
        String chatMemoryId = memoryService.aiChatKey(context.getUserId(), context.getSessionId());
        context.setMemoryId(chatMemoryId);
        IntentRoutingResult routing = intentRouter.route(request.getMessage(), request.getShopId());
        context.setIntent(routing.getIntent());
        if (!isBlank(routing.getClarification())) {
            return response(context, routing.getClarification(), false);
        }
        switch (routing.getIntent()) {
            case SUMMARY:
                ShopSummaryResult summary = summaryWorkflow.execute(context, SummaryWorkflowRequest.builder()
                        .shopId(routing.getShopId())
                        .writeMemory(true)
                        .build());
                return ShopAIResponse.builder()
                        .response(summary.getCoreSummary())
                        .answer(summary.getCoreSummary())
                        .shopId(summary.getShopId())
                        .sessionId(context.getSessionId())
                        .memoryId(context.getMemoryId())
                        .traceId(context.getTraceId())
                        .analysis(com.hmdp.dto.ai.ShopAIAnalysisResult.builder()
                                .summary(summary.getCoreSummary())
                                .sentiment(summary.getOverallSentiment())
                                .keywords(summary.getKeyPoints())
                                .build())
                        .evidence(Collections.emptyList())
                        .degraded(false)
                        .cacheHit(false)
                        .usedTools(Collections.emptyList())
                        .build();
            case QA:
                return qaWorkflow.execute(context, QAWorkflowRequest.builder()
                        .shopId(routing.getShopId())
                        .question(request.getMessage())
                        .build());
            case COMPARE:
                return compareWorkflow.execute(context, CompareWorkflowRequest.builder()
                        .shopId1(routing.getShopId1())
                        .shopId2(routing.getShopId2())
                        .aspect(routing.getAspect())
                        .build());
            case RECOMMEND:
                return recommendWorkflow.execute(context, RecommendWorkflowRequest.builder()
                        .userPreference(routing.getUserPreference())
                        .category(routing.getCategory())
                        .limit(routing.getLimit())
                        .build());
            case FREE_CHAT:
            case UNSUPPORTED:
            default:
                return freeChat(context, request.getMessage());
        }
    }

    private ShopAIResponse freeChat(ShopAIRequestContext context, String message) {
        String prompt = promptTemplateRegistry.freeChatPrompt(message);
        boolean degraded = false;
        String answer;
        if (fallbackPolicy.shouldUseFallback("analyzeShopData")) {
            answer = fallbackPolicy.fallbackText(context.getMemoryId(), prompt, "chat");
            degraded = true;
        } else {
            try {
                answer = modelGateway.generateFreeChat(context.getMemoryId(), prompt);
                QualityCheck quality = qualityGuard.validateText(answer, "chat");
                if (!quality.pass()) {
                    answer = "我可以帮你做店铺总结、评价问答、店铺对比和推荐。请提供店铺ID或你的推荐偏好。";
                    degraded = true;
                } else {
                    answer = qualityGuard.postProcess(answer);
                }
            } catch (Exception e) {
                fallbackPolicy.recordFailure("analyzeShopData");
                answer = fallbackPolicy.fallbackText(context.getMemoryId(), prompt, "chat");
                degraded = true;
            }
        }
        return response(context, answer, degraded);
    }

    private ShopAIResponse response(ShopAIRequestContext context, String answer, boolean degraded) {
        return ShopAIResponse.builder()
                .response(answer)
                .answer(answer)
                .sessionId(context.getSessionId())
                .memoryId(context.getMemoryId())
                .traceId(context.getTraceId())
                .evidence(Collections.emptyList())
                .degraded(degraded)
                .cacheHit(false)
                .usedTools(Collections.emptyList())
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
