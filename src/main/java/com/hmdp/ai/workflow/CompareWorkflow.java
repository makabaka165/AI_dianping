package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.GovernedGeneration;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.port.ShopDataPort;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.CompareWorkflowRequest;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.retrieval.ShopContextAssembler;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class CompareWorkflow {

    private static final String ANALYSIS_TYPE = "compare";

    @Resource
    private ShopContextAssembler shopContextAssembler;

    @Resource
    private ShopDataPort shopDataPort;

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
    private GovernedGeneration governedGeneration;

    @Resource
    private AiMetricsService aiMetricsService;

    public ShopAIResponse execute(ShopAIRequestContext context, CompareWorkflowRequest request) {
        long start = System.currentTimeMillis();
        validate(request);
        String memoryId = memoryService.shopCompareKey(context.getUserId(), context.getSessionId());
        context.setMemoryId(memoryId);
        List<String> availabilityIssues = availabilityIssues(request);
        if (!availabilityIssues.isEmpty()) {
            ShopCompareResult compare = unavailableCompare(request, availabilityIssues);
            return response(context, Collections.emptyList(), compare, false, 0.2, null,
                    PromptTemplateRegistry.COMPARE_VERSION);
        }
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
        GovernedGeneration.GovernedResult<ShopCompareResult> generated = governedGeneration.runWithReason(
                () -> modelGateway.generateStructuredComparison(memoryId, prompt.getContent(), request.getShopId1(),
                        request.getShopId2(), request.getAspect(), evidence),
                reason -> modelGateway.repairStructuredComparison(memoryId, prompt.getContent(), request.getShopId1(),
                        request.getShopId2(), request.getAspect(), reason),
                compare -> qualityGuard.validateCompare(compare, request.getShopId1(), request.getShopId2(),
                        evidence, ANALYSIS_TYPE),
                reason -> fallbackPolicy.fallbackCompare(request.getShopId1(), request.getShopId2(),
                        request.getAspect(), ANALYSIS_TYPE, reason),
                compare -> {
                    compare.setConclusion(qualityGuard.postProcess(compare.getConclusion()));
                    return compare;
                });
        ShopCompareResult compare = generated.getValue();
        boolean degraded = generated.isDegraded();
        String fallbackReason = generated.getFallbackReason() == null ? null : generated.getFallbackReason().name();
        aiMetricsService.recordDuration(ANALYSIS_TYPE, System.currentTimeMillis() - start, degraded);
        aiMetricsService.recordEvidenceCount(ANALYSIS_TYPE, evidence.size(), "hybrid");
        return response(context, evidence, compare, degraded, degraded ? 0.35 : 0.7,
                fallbackReason,
                prompt.getVersion());
    }

    public StreamWorkflowPlan prepareStreamPlan(ShopAIRequestContext context, CompareWorkflowRequest request) {
        validate(request);
        String memoryId = memoryService.shopCompareKey(context.getUserId(), context.getSessionId());
        context.setMemoryId(memoryId);
        List<String> availabilityIssues = availabilityIssues(request);
        if (!availabilityIssues.isEmpty()) {
            return StreamWorkflowPlan.builder()
                    .analysisType(ANALYSIS_TYPE)
                    .memoryId(memoryId)
                    .directText(String.join("；", availabilityIssues))
                    .evidence(Collections.emptyList())
                    .confidence(0.2)
                    .degraded(false)
                    .cacheHit(false)
                    .build();
        }
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
                .structuredOutput(true)
                .expectedShopId1(request.getShopId1())
                .expectedShopId2(request.getShopId2())
                .expectedAspect(request.getAspect())
                .build();
    }

    private void validate(CompareWorkflowRequest request) {
        if (request.getShopId1() == null || request.getShopId1() <= 0
                || request.getShopId2() == null || request.getShopId2() <= 0) {
            throw new IllegalArgumentException("店铺ID必须是正数");
        }
    }

    private List<String> availabilityIssues(CompareWorkflowRequest request) {
        List<String> issues = new ArrayList<>();
        addAvailabilityIssue(issues, "店铺1", request.getShopId1());
        addAvailabilityIssue(issues, "店铺2", request.getShopId2());
        return issues;
    }

    private void addAvailabilityIssue(List<String> issues, String label, Long shopId) {
        if (!shopDataPort.shopExists(shopId)) {
            issues.add(label + "不存在");
            return;
        }
        if (shopDataPort.getReviewCount(shopId) <= 0) {
            issues.add(label + "暂无评价数据");
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

    private ShopCompareResult unavailableCompare(CompareWorkflowRequest request, List<String> issues) {
        return ShopCompareResult.builder()
                .shopId1(request.getShopId1())
                .shopId2(request.getShopId2())
                .aspect(request.getAspect())
                .conclusion(String.join("；", issues))
                .winnerByAspect(ShopCompareResult.INSUFFICIENT)
                .shop1Score(0)
                .shop2Score(0)
                .shop1Pros(Collections.emptyList())
                .shop2Pros(Collections.emptyList())
                .riskNotes(issues)
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
