package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.fallback.FallbackReason;
import com.hmdp.ai.guard.GovernedGeneration;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.port.ShopDataPort;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.retrieval.ShopContextAssembler;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

@Component
public class QAWorkflow {

    private static final String ANALYSIS_TYPE = "ask";
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
        String availabilityMessage = availabilityMessage(request.getShopId());
        if (availabilityMessage != null) {
            return insufficientEvidence(request.getShopId(), request.getQuestion(), context, availabilityMessage);
        }
        ShopAnalysisContext shopContext = shopContextAssembler.buildForShop(request.getShopId(), request.getQuestion());
        if (shopContext.safeEvidence().isEmpty()) {
            return insufficientEvidence(request.getShopId(), request.getQuestion(), context,
                    "当前评价证据不足以判断店铺" + request.getShopId() + "的情况。");
        }
        String summaryMemory = memoryService.readSummaryMemory(memoryService.shopSummaryKey(request.getShopId(), context.getUserId()));
        PromptTemplateRender prompt = promptTemplateRegistry.renderQA(
                context,
                request.getShopId(),
                request.getQuestion(),
                summaryMemory,
                shopContextAssembler.toPromptBlock(shopContext));
        GovernedGeneration.GovernedResult<ShopQAResult> generated = governedGeneration.runWithReason(
                () -> modelGateway.generateStructuredAnswer(memoryId, prompt.getContent(), request.getShopId(),
                        request.getQuestion(), shopContext.safeEvidence()),
                reason -> modelGateway.repairStructuredAnswer(memoryId, prompt.getContent(), request.getShopId(),
                        request.getQuestion(), reason),
                qa -> qualityGuard.validateQA(qa, request.getShopId(), shopContext.safeEvidence(), ANALYSIS_TYPE),
                reason -> fallbackPolicy.fallbackQA(request.getShopId(), request.getQuestion(), ANALYSIS_TYPE, reason),
                qa -> {
                    qa.setAnswer(qualityGuard.postProcess(qa.getAnswer()));
                    return qa;
                });
        ShopQAResult qa = generated.getValue();
        boolean degraded = generated.isDegraded();
        String fallbackReason = generated.getFallbackReason() == null ? null : generated.getFallbackReason().name();
        aiMetricsService.recordDuration(ANALYSIS_TYPE, System.currentTimeMillis() - start, degraded);
        aiMetricsService.recordEvidenceCount(ANALYSIS_TYPE, shopContext.safeEvidence().size(), "hybrid");
        return response(context, shopContext.safeEvidence(), qa, degraded, degraded ? 0.35 : 0.7, fallbackReason,
                prompt.getVersion());
    }

    public StreamWorkflowPlan prepareStreamPlan(ShopAIRequestContext context, QAWorkflowRequest request) {
        if (request.getShopId() == null || request.getShopId() <= 0) {
            throw new IllegalArgumentException("shopId must be positive");
        }
        String memoryId = memoryService.shopQAKey(request.getShopId(), context.getUserId());
        context.setMemoryId(memoryId);
        String availabilityMessage = availabilityMessage(request.getShopId());
        if (availabilityMessage != null) {
            return StreamWorkflowPlan.builder()
                    .analysisType(ANALYSIS_TYPE)
                    .memoryId(memoryId)
                    .directText(availabilityMessage)
                    .evidence(Collections.emptyList())
                    .confidence(0.2)
                    .degraded(false)
                    .cacheHit(false)
                    .build();
        }
        ShopAnalysisContext shopContext = shopContextAssembler.buildForShop(request.getShopId(), request.getQuestion());
        if (shopContext.safeEvidence().isEmpty()) {
            return StreamWorkflowPlan.builder()
                    .analysisType(ANALYSIS_TYPE)
                    .memoryId(memoryId)
                    .directText("当前评价证据不足以判断店铺" + request.getShopId() + "的情况。")
                    .evidence(Collections.emptyList())
                    .confidence(0.2)
                    .degraded(false)
                    .cacheHit(false)
                    .build();
        }
        String summaryMemory = memoryService.readSummaryMemory(memoryService.shopSummaryKey(request.getShopId(), context.getUserId()));
        PromptTemplateRender prompt = promptTemplateRegistry.renderQA(
                context,
                request.getShopId(),
                request.getQuestion(),
                summaryMemory,
                shopContextAssembler.toPromptBlock(shopContext));
        return StreamWorkflowPlan.builder()
                .analysisType(ANALYSIS_TYPE)
                .memoryId(memoryId)
                .prompt(prompt.getContent())
                .promptVersion(prompt.getVersion())
                .evidence(shopContext.safeEvidence())
                .confidence(0.7)
                .degraded(false)
                .cacheHit(false)
                .structuredOutput(true)
                .expectedShopId(request.getShopId())
                .expectedQuestion(request.getQuestion())
                .build();
    }

    private ShopAIResponse insufficientEvidence(Long shopId,
                                                String question,
                                                ShopAIRequestContext context,
                                                String answer) {
        ShopQAResult qa = ShopQAResult.builder()
                .shopId(shopId)
                .question(question)
                .answer(answer)
                .evidenceIds(Collections.emptyList())
                .insufficientEvidence(true)
                .build();
        return response(context, Collections.emptyList(), qa, false, 0.2, null, PromptTemplateRegistry.QA_VERSION);
    }

    private String availabilityMessage(Long shopId) {
        if (!shopDataPort.shopExists(shopId)) {
            return "店铺不存在";
        }
        if (shopDataPort.getReviewCount(shopId) <= 0) {
            return "暂无评价数据";
        }
        return null;
    }

    private ShopAIResponse response(ShopAIRequestContext context,
                                    List<EvidenceItem> evidence,
                                    ShopQAResult qa,
                                    boolean degraded,
                                    double confidence,
                                    String fallbackReason,
                                    String promptVersion) {
        return ShopAIResponse.builder()
                .qa(qa)
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

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
