package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.ShopContextAssembler;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;

@Component
public class QAWorkflow implements ShopAIWorkflow<QAWorkflowRequest, ShopAIResponse> {

    @Resource
    private ShopContextAssembler shopContextAssembler;

    @Resource
    private PromptTemplateRegistry promptTemplateRegistry;

    @Resource
    private MemoryService memoryService;

    @Resource
    private ModelGateway modelGateway;

    @Resource
    private QualityGuard qualityGuard;

    @Resource
    private FallbackPolicy fallbackPolicy;

    @Resource
    private AiMetricsService aiMetricsService;

    @Override
    public ShopAIIntent intent() {
        return ShopAIIntent.QA;
    }

    @Override
    public ShopAIResponse execute(ShopAIRequestContext context, QAWorkflowRequest request) {
        long start = System.currentTimeMillis();
        if (request.getShopId() == null || request.getShopId() <= 0) {
            throw new IllegalArgumentException("店铺ID必须是正数");
        }
        if (isBlank(request.getQuestion())) {
            throw new IllegalArgumentException("问题不能为空");
        }

        String memoryId = memoryService.shopQAKey(request.getShopId(), context.getUserId());
        context.setMemoryId(memoryId);
        ShopAnalysisContext shopContext = shopContextAssembler.buildForShop(request.getShopId(), request.getQuestion());
        if (shopContext.safeEvidence().isEmpty()) {
            return insufficientEvidence(request.getShopId(), context, "当前评价证据不足以判断店铺" + request.getShopId() + "的情况。");
        }

        String summaryMemory = memoryService.readSummaryMemory(
                memoryService.shopSummaryKey(request.getShopId(), context.getUserId()));
        String prompt = promptTemplateRegistry.qaPrompt(
                request.getQuestion(),
                summaryMemory,
                shopContextAssembler.toPromptBlock(shopContext));
        boolean degraded = false;
        String answer;
        if (fallbackPolicy.shouldUseFallback("analyzeShopData")) {
            answer = fallbackPolicy.fallbackText(memoryId, prompt, "ask");
            degraded = true;
        } else {
            try {
                answer = modelGateway.generateAnswer(memoryId, prompt);
                QualityCheck quality = qualityGuard.validateText(answer, "ask");
                if (!quality.pass()) {
                    answer = fallbackPolicy.fallbackText(memoryId, prompt, "ask");
                    degraded = true;
                } else {
                    answer = qualityGuard.postProcess(answer);
                }
            } catch (Exception e) {
                fallbackPolicy.recordFailure("analyzeShopData");
                answer = fallbackPolicy.fallbackText(memoryId, prompt, "ask");
                degraded = true;
            }
        }
        aiMetricsService.recordDuration("ask", System.currentTimeMillis() - start, degraded);
        aiMetricsService.increment("ai.evidence.count", "ask", degraded);
        return ShopAIResponse.builder()
                .answer(answer)
                .response(answer)
                .shopId(request.getShopId())
                .sessionId(context.getSessionId())
                .memoryId(memoryId)
                .traceId(context.getTraceId())
                .evidence(shopContext.safeEvidence())
                .confidence(degraded ? 0.35 : 0.75)
                .degraded(degraded)
                .cacheHit(false)
                .usedTools(Collections.emptyList())
                .build();
    }

    public StreamWorkflowPlan prepareStreamPlan(ShopAIRequestContext context, QAWorkflowRequest request) {
        if (request.getShopId() == null || request.getShopId() <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }
        if (isBlank(request.getQuestion())) {
            throw new IllegalArgumentException("question must not be blank");
        }

        String memoryId = memoryService.shopQAKey(request.getShopId(), context.getUserId());
        context.setMemoryId(memoryId);
        ShopAnalysisContext shopContext = shopContextAssembler.buildForShop(request.getShopId(), request.getQuestion());
        if (shopContext.safeEvidence().isEmpty()) {
            return StreamWorkflowPlan.builder()
                    .analysisType("ask")
                    .memoryId(memoryId)
                    .directText("当前评价证据不足以判断店铺" + request.getShopId() + "的情况。")
                    .evidence(Collections.emptyList())
                    .confidence(0.2)
                    .degraded(false)
                    .cacheHit(false)
                    .build();
        }

        String summaryMemory = memoryService.readSummaryMemory(
                memoryService.shopSummaryKey(request.getShopId(), context.getUserId()));
        String prompt = promptTemplateRegistry.qaPrompt(
                request.getQuestion(),
                summaryMemory,
                shopContextAssembler.toPromptBlock(shopContext));

        return StreamWorkflowPlan.builder()
                .analysisType("ask")
                .memoryId(memoryId)
                .prompt(prompt)
                .evidence(shopContext.safeEvidence())
                .confidence(0.75)
                .degraded(false)
                .cacheHit(false)
                .build();
    }

    private ShopAIResponse insufficientEvidence(Long shopId, ShopAIRequestContext context, String answer) {
        return ShopAIResponse.builder()
                .answer(answer)
                .response(answer)
                .shopId(shopId)
                .sessionId(context.getSessionId())
                .memoryId(context.getMemoryId())
                .traceId(context.getTraceId())
                .evidence(Collections.emptyList())
                .confidence(0.2)
                .degraded(false)
                .cacheHit(false)
                .usedTools(Collections.emptyList())
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
