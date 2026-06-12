package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.CompareWorkflowRequest;
import com.hmdp.dto.ai.ReviewEvidence;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.ShopContextAssembler;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class CompareWorkflow implements ShopAIWorkflow<CompareWorkflowRequest, ShopAIResponse> {

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
        return ShopAIIntent.COMPARE;
    }

    @Override
    public ShopAIResponse execute(ShopAIRequestContext context, CompareWorkflowRequest request) {
        long start = System.currentTimeMillis();
        if (request.getShopId1() == null || request.getShopId1() <= 0
                || request.getShopId2() == null || request.getShopId2() <= 0) {
            throw new IllegalArgumentException("店铺ID必须是正数");
        }
        String memoryId = memoryService.shopCompareKey(context.getUserId(), context.getSessionId());
        context.setMemoryId(memoryId);
        ShopAnalysisContext context1 = shopContextAssembler.buildForCompare(request.getShopId1(), "店铺对比", request.getAspect());
        ShopAnalysisContext context2 = shopContextAssembler.buildForCompare(request.getShopId2(), "店铺对比", request.getAspect());
        List<ReviewEvidence> evidence = new ArrayList<>();
        evidence.addAll(context1.safeEvidence());
        evidence.addAll(context2.safeEvidence());
        if (evidence.isEmpty()) {
            String response = "当前评价证据不足以判断两家店铺的对比表现。";
            return ShopAIResponse.builder()
                    .comparison(response)
                    .response(response)
                    .sessionId(context.getSessionId())
                    .memoryId(memoryId)
                    .traceId(context.getTraceId())
                    .evidence(evidence)
                    .degraded(false)
                    .cacheHit(false)
                    .usedTools(Collections.emptyList())
                    .build();
        }

        String prompt = promptTemplateRegistry.comparePrompt(
                request.getAspect(),
                shopContextAssembler.toPromptBlock(context1),
                shopContextAssembler.toPromptBlock(context2));
        boolean degraded = false;
        String comparison;
        if (fallbackPolicy.shouldUseFallback("analyzeShopData")) {
            comparison = fallbackPolicy.fallbackText(memoryId, prompt, "compare");
            degraded = true;
        } else {
            try {
                comparison = modelGateway.generateComparison(memoryId, prompt);
                QualityCheck quality = qualityGuard.validateText(comparison, "compare");
                if (!quality.pass()) {
                    comparison = fallbackPolicy.fallbackText(memoryId, prompt, "compare");
                    degraded = true;
                } else {
                    comparison = qualityGuard.postProcess(comparison);
                }
            } catch (Exception e) {
                fallbackPolicy.recordFailure("analyzeShopData");
                comparison = fallbackPolicy.fallbackText(memoryId, prompt, "compare");
                degraded = true;
            }
        }
        aiMetricsService.recordDuration("compare", System.currentTimeMillis() - start, degraded);
        return ShopAIResponse.builder()
                .comparison(comparison)
                .response(comparison)
                .sessionId(context.getSessionId())
                .memoryId(memoryId)
                .traceId(context.getTraceId())
                .evidence(evidence)
                .winnerByAspect("需结合用户偏好判断")
                .degraded(degraded)
                .cacheHit(false)
                .usedTools(Collections.emptyList())
                .build();
    }
    public StreamWorkflowPlan prepareStreamPlan(ShopAIRequestContext context, CompareWorkflowRequest request) {
        if (request.getShopId1() == null || request.getShopId1() <= 0
                || request.getShopId2() == null || request.getShopId2() <= 0) {
            throw new IllegalArgumentException("shopIds must be positive");
        }
        String memoryId = memoryService.shopCompareKey(context.getUserId(), context.getSessionId());
        context.setMemoryId(memoryId);
        ShopAnalysisContext context1 = shopContextAssembler.buildForCompare(request.getShopId1(), "shop compare", request.getAspect());
        ShopAnalysisContext context2 = shopContextAssembler.buildForCompare(request.getShopId2(), "shop compare", request.getAspect());
        List<ReviewEvidence> evidence = new ArrayList<>();
        evidence.addAll(context1.safeEvidence());
        evidence.addAll(context2.safeEvidence());
        if (evidence.isEmpty()) {
            return StreamWorkflowPlan.builder()
                    .analysisType("compare")
                    .memoryId(memoryId)
                    .directText("当前评价证据不足以判断两家店铺的对比表现。")
                    .evidence(evidence)
                    .confidence(0.2)
                    .degraded(false)
                    .cacheHit(false)
                    .build();
        }

        String prompt = promptTemplateRegistry.comparePrompt(
                request.getAspect(),
                shopContextAssembler.toPromptBlock(context1),
                shopContextAssembler.toPromptBlock(context2));
        return StreamWorkflowPlan.builder()
                .analysisType("compare")
                .memoryId(memoryId)
                .prompt(prompt)
                .evidence(evidence)
                .confidence(0.7)
                .degraded(false)
                .cacheHit(false)
                .build();
    }
}
