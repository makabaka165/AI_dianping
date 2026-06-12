package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.CompareWorkflowRequest;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.service.AiMetricsService;
import com.hmdp.service.ShopContextAssembler;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class CompareWorkflow implements ShopAIWorkflow<CompareWorkflowRequest, ShopAIResponse> {

    private static final String ANALYSIS_TYPE = "compare";
    private static final String MODEL_OPERATION = "compare:analyzeShopData";

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
        validate(request);
        String memoryId = memoryService.shopCompareKey(context.getUserId(), context.getSessionId());
        context.setMemoryId(memoryId);
        ShopAnalysisContext context1 = shopContextAssembler.buildForCompare(request.getShopId1(), "店铺对比", request.getAspect());
        ShopAnalysisContext context2 = shopContextAssembler.buildForCompare(request.getShopId2(), "店铺对比", request.getAspect());
        List<EvidenceItem> evidence = mergeEvidence(context1, context2);
        if (evidence.isEmpty()) {
            ShopCompareResult compare = insufficientCompare(request);
            return response(context, evidence, compare, false, 0.2, null, PromptTemplateRegistry.COMPARE_VERSION);
        }

        PromptTemplateRender prompt = promptTemplateRegistry.renderCompare(
                context,
                request.getShopId1(),
                request.getShopId2(),
                request.getAspect(),
                shopContextAssembler.toPromptBlock(context1),
                shopContextAssembler.toPromptBlock(context2));
        boolean degraded = false;
        ShopCompareResult compare;
        if (fallbackPolicy.shouldUseFallback(MODEL_OPERATION)) {
            compare = fallbackPolicy.fallbackCompare(request.getShopId1(), request.getShopId2(), request.getAspect(), ANALYSIS_TYPE);
            degraded = true;
        } else {
            try {
                compare = modelGateway.generateStructuredComparison(memoryId, prompt.getContent(), request.getShopId1(),
                        request.getShopId2(), request.getAspect(), evidence);
                QualityCheck quality = qualityGuard.validateCompare(compare, request.getShopId1(), request.getShopId2(),
                        evidence, ANALYSIS_TYPE);
                if (!quality.pass()) {
                    compare = modelGateway.repairStructuredComparison(memoryId, prompt.getContent(), request.getShopId1(),
                            request.getShopId2(), request.getAspect(), quality.getReason());
                    quality = qualityGuard.validateCompare(compare, request.getShopId1(), request.getShopId2(),
                            evidence, ANALYSIS_TYPE);
                    if (!quality.pass()) {
                        compare = fallbackPolicy.fallbackCompare(request.getShopId1(), request.getShopId2(),
                                request.getAspect(), ANALYSIS_TYPE);
                        degraded = true;
                    } else {
                        compare.setConclusion(qualityGuard.postProcess(compare.getConclusion()));
                    }
                } else {
                    compare.setConclusion(qualityGuard.postProcess(compare.getConclusion()));
                }
            } catch (Exception e) {
                fallbackPolicy.recordFailure(MODEL_OPERATION);
                compare = fallbackPolicy.fallbackCompare(request.getShopId1(), request.getShopId2(),
                        request.getAspect(), ANALYSIS_TYPE);
                degraded = true;
            }
        }
        aiMetricsService.recordDuration(ANALYSIS_TYPE, System.currentTimeMillis() - start, degraded);
        aiMetricsService.recordEvidenceCount(ANALYSIS_TYPE, evidence.size(), "hybrid");
        return response(context, evidence, compare, degraded, degraded ? 0.35 : 0.7,
                degraded ? "AI_MODEL_OR_QUALITY_FALLBACK" : null,
                prompt.getVersion());
    }

    public StreamWorkflowPlan prepareStreamPlan(ShopAIRequestContext context, CompareWorkflowRequest request) {
        validate(request);
        String memoryId = memoryService.shopCompareKey(context.getUserId(), context.getSessionId());
        context.setMemoryId(memoryId);
        ShopAnalysisContext context1 = shopContextAssembler.buildForCompare(request.getShopId1(), "shop compare", request.getAspect());
        ShopAnalysisContext context2 = shopContextAssembler.buildForCompare(request.getShopId2(), "shop compare", request.getAspect());
        List<EvidenceItem> evidence = mergeEvidence(context1, context2);
        if (evidence.isEmpty()) {
            return StreamWorkflowPlan.builder()
                    .analysisType(ANALYSIS_TYPE)
                    .memoryId(memoryId)
                    .directText("当前评价证据不足以判断两家店铺的对比表现。")
                    .evidence(evidence)
                    .confidence(0.2)
                    .degraded(false)
                    .cacheHit(false)
                    .build();
        }

        PromptTemplateRender prompt = promptTemplateRegistry.renderCompare(
                context,
                request.getShopId1(),
                request.getShopId2(),
                request.getAspect(),
                shopContextAssembler.toPromptBlock(context1),
                shopContextAssembler.toPromptBlock(context2));
        return StreamWorkflowPlan.builder()
                .analysisType(ANALYSIS_TYPE)
                .memoryId(memoryId)
                .prompt(prompt.getContent())
                .promptVersion(prompt.getVersion())
                .evidence(evidence)
                .confidence(0.7)
                .degraded(false)
                .cacheHit(false)
                .build();
    }

    private void validate(CompareWorkflowRequest request) {
        if (request.getShopId1() == null || request.getShopId1() <= 0
                || request.getShopId2() == null || request.getShopId2() <= 0) {
            throw new IllegalArgumentException("店铺ID必须是正数");
        }
    }

    private List<EvidenceItem> mergeEvidence(ShopAnalysisContext context1, ShopAnalysisContext context2) {
        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.addAll(context1.safeEvidence());
        evidence.addAll(context2.safeEvidence());
        return evidence;
    }

    private ShopCompareResult insufficientCompare(CompareWorkflowRequest request) {
        return ShopCompareResult.builder()
                .shopId1(request.getShopId1())
                .shopId2(request.getShopId2())
                .aspect(request.getAspect())
                .conclusion("当前评价证据不足以判断两家店铺的对比表现。")
                .winnerByAspect(ShopCompareResult.INSUFFICIENT)
                .shop1Score(0)
                .shop2Score(0)
                .shop1Pros(Collections.emptyList())
                .shop2Pros(Collections.emptyList())
                .riskNotes(List.of("证据不足，不能可靠判断"))
                .evidenceIds(Collections.emptyList())
                .build();
    }

    private ShopAIResponse response(ShopAIRequestContext context,
                                    List<EvidenceItem> evidence,
                                    ShopCompareResult compare,
                                    boolean degraded,
                                    double confidence,
                                    String fallbackReason,
                                    String promptVersion) {
        return ShopAIResponse.builder()
                .compare(compare)
                .sessionId(context.getSessionId())
                .memoryId(context.getMemoryId())
                .traceId(context.getTraceId())
                .promptVersion(promptVersion)
                .evidence(evidence)
                .confidence(confidence)
                .degraded(degraded)
                .cacheHit(false)
                .fallbackReason(fallbackReason)
                .build();
    }
}
