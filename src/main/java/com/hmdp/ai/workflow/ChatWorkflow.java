package com.hmdp.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.fallback.FallbackReason;
import com.hmdp.ai.guard.GovernedGeneration;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.intent.IntentRouteCoordinator;
import com.hmdp.dto.ai.IntentRoutingResult;
import com.hmdp.dto.ai.ShopAIIntent;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.model.StructuredOutputParser;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRender;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.ChatWorkflowRequest;
import com.hmdp.ai.workflow.request.CompareWorkflowRequest;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.ai.workflow.request.RecommendWorkflowRequest;
import com.hmdp.ai.workflow.request.SummaryWorkflowRequest;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAIStreamEvent;
import com.hmdp.dto.ai.ShopChatResult;
import com.hmdp.dto.ai.ShopCompareResult;
import com.hmdp.dto.ai.ShopQAResult;
import com.hmdp.dto.ai.ShopRecommendResult;
import com.hmdp.dto.ai.ShopRecommendationItem;
import com.hmdp.dto.ai.ShopSummaryResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ChatWorkflow {

    private static final String CHAT_ANALYSIS_TYPE = "chat";
    private static final int MAX_STREAM_PARTS = 4096;

    @Resource
    private IntentRouteCoordinator intentRouteCoordinator;

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

    @Resource
    private GovernedGeneration governedGeneration;

    @Resource
    private StructuredOutputParser structuredOutputParser;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${hmdp.ai.stream.max-response-chars:100000}")
    private int maxStreamResponseChars = 100_000;

    public ShopAIResponse execute(ShopAIRequestContext context, ChatWorkflowRequest request) {
        if (request == null || isBlank(request.getMessage())) {
            throw new IllegalArgumentException("消息不能为空");
        }
        String chatMemoryId = memoryService.aiChatKey(context.getUserId(), context.getSessionId());
        context.setMemoryId(chatMemoryId);
        IntentRoutingResult routing = intentRouteCoordinator.route(context, request.getMessage(), request.getShopId());
        context.setIntent(routing.getIntent());
        if (!isBlank(routing.getClarification())) {
            return withRouting(chatResponse(context, routing.getClarification(), false, true), routing);
        }
        switch (routing.getIntent()) {
            case SUMMARY:
                ShopSummaryResult summary = summaryWorkflow.execute(context, SummaryWorkflowRequest.builder()
                        .shopId(routing.getShopId())
                        .writeMemory(true)
                        .build());
                return withRouting(ShopAIResponse.builder()
                        .summary(summary)
                        .sessionId(context.getSessionId())
                        .memoryId(context.getMemoryId())
                        .traceId(context.getTraceId())
                        .promptVersion(summary.getPromptVersion())
                        .evidence(safeEvidence(summary.getEvidence()))
                        .confidence(summary.getConfidence())
                        .degraded(Boolean.TRUE.equals(summary.getDegraded()))
                        .cacheHit(Boolean.TRUE.equals(summary.getCacheHit()))
                        .fallbackReason(summary.getFallbackReason())
                        .build(), routing);
            case QA:
                return withRouting(qaWorkflow.execute(context, QAWorkflowRequest.builder()
                        .shopId(routing.getShopId())
                        .question(request.getMessage())
                        .build()), routing);
            case COMPARE:
                return withRouting(compareWorkflow.execute(context, CompareWorkflowRequest.builder()
                        .shopId1(routing.getShopId1())
                        .shopId2(routing.getShopId2())
                        .aspect(routing.getAspect())
                        .build()), routing);
            case RECOMMEND:
                return withRouting(recommendWorkflow.execute(context, RecommendWorkflowRequest.builder()
                        .userPreference(routing.getUserPreference())
                        .category(routing.getCategory())
                        .limit(routing.getLimit())
                        .build()), routing);
            case FREE_CHAT:
            case UNSUPPORTED:
            default:
                return withRouting(freeChat(context, request.getMessage()), routing);
        }
    }

    public Flux<ServerSentEvent<ShopAIStreamEvent>> stream(ShopAIRequestContext context, ChatWorkflowRequest request) {
        if (request == null || isBlank(request.getMessage())) {
            return streamText(context, null, "消息不能为空", false, 0.2);
        }
        String chatMemoryId = memoryService.aiChatKey(context.getUserId(), context.getSessionId());
        context.setMemoryId(chatMemoryId);
        IntentRoutingResult routing = intentRouteCoordinator.route(context, request.getMessage(), request.getShopId());
        context.setIntent(routing.getIntent());
        if (!isBlank(routing.getClarification())) {
            return streamText(context, routing, routing.getClarification(), false, 0.2);
        }
        try {
            switch (routing.getIntent()) {
                case SUMMARY:
                    ShopSummaryResult summary = summaryWorkflow.execute(context, SummaryWorkflowRequest.builder()
                            .shopId(routing.getShopId())
                            .writeMemory(true)
                            .build());
                    ShopAIResponse summaryResponse = ShopAIResponse.builder()
                            .summary(summary)
                            .sessionId(context.getSessionId())
                            .memoryId(context.getMemoryId())
                            .traceId(context.getTraceId())
                            .promptVersion(summary.getPromptVersion())
                            .evidence(safeEvidence(summary.getEvidence()))
                            .confidence(summary.getConfidence())
                            .degraded(Boolean.TRUE.equals(summary.getDegraded()))
                            .cacheHit(Boolean.TRUE.equals(summary.getCacheHit()))
                            .fallbackReason(summary.getFallbackReason())
                            .build();
                    return streamResponse(context, routing, withRouting(summaryResponse, routing));
                case QA:
                    return streamPlan(context, routing, qaWorkflow.prepareStreamPlan(context, QAWorkflowRequest.builder()
                            .shopId(routing.getShopId())
                            .question(request.getMessage())
                            .build()));
                case COMPARE:
                    return streamPlan(context, routing, compareWorkflow.prepareStreamPlan(context, CompareWorkflowRequest.builder()
                            .shopId1(routing.getShopId1())
                            .shopId2(routing.getShopId2())
                            .aspect(routing.getAspect())
                            .build()));
                case RECOMMEND:
                    return streamPlan(context, routing, recommendWorkflow.prepareStreamPlan(context, RecommendWorkflowRequest.builder()
                            .userPreference(routing.getUserPreference())
                            .category(routing.getCategory())
                            .limit(routing.getLimit())
                            .build()));
                case FREE_CHAT:
                case UNSUPPORTED:
                default:
                    return freeChatStream(context, routing, request.getMessage());
            }
        } catch (Exception e) {
            PromptTemplateRender prompt = promptTemplateRegistry.renderFreeChat(context, request.getMessage());
            return streamText(context, routing, fallbackPolicy.fallbackText(context.getMemoryId(),
                    prompt.getContent(), CHAT_ANALYSIS_TYPE), true, 0.35);
        }
    }

    private ShopAIResponse freeChat(ShopAIRequestContext context, String message) {
        PromptTemplateRender prompt = promptTemplateRegistry.renderFreeChat(context, message);
        GovernedGeneration.GovernedResult<String> generated = governedGeneration.runWithReason(
                () -> modelGateway.generateFreeChat(context.getMemoryId(), prompt.getContent()),
                reason -> modelGateway.repairFreeChat(context.getMemoryId(), prompt.getContent(), reason),
                text -> qualityGuard.validateText(text, CHAT_ANALYSIS_TYPE),
                reason -> fallbackPolicy.fallbackChat(message, CHAT_ANALYSIS_TYPE, reason).getMessage(),
                text -> qualityGuard.postProcess(text));
        ShopChatResult chat = ShopChatResult.builder()
                .message(generated.getValue())
                .clarification(false)
                .build();
        ShopAIResponse response = chatResponse(context, chat.getMessage(), generated.isDegraded(), Boolean.TRUE.equals(chat.getClarification()));
        response.setPromptVersion(prompt.getVersion());
        response.setFallbackReason(generated.getFallbackReason() == null ? null : generated.getFallbackReason().name());
        return response;
    }

    private ShopAIResponse chatResponse(ShopAIRequestContext context, String message, boolean degraded, boolean clarification) {
        return ShopAIResponse.builder()
                .chat(ShopChatResult.builder()
                        .message(message)
                        .clarification(clarification)
                        .build())
                .sessionId(context.getSessionId())
                .memoryId(context.getMemoryId())
                .traceId(context.getTraceId())
                .evidence(Collections.emptyList())
                .degraded(degraded)
                .cacheHit(false)
                .build();
    }

    private Flux<ServerSentEvent<ShopAIStreamEvent>> freeChatStream(ShopAIRequestContext context,
                                                                    IntentRoutingResult routing,
                                                                    String message) {
        PromptTemplateRender prompt = promptTemplateRegistry.renderFreeChat(context, message);
        return Flux.concat(
                Flux.just(metadataEvent(context, routing, context.getMemoryId(), prompt.getVersion())),
                modelGateway.streamChat(context.getMemoryId(), prompt.getContent())
                        .collect(this::newStreamBuffer, StreamBuffer::append)
                        .flatMapMany(buffer -> streamAuditedFreeChatText(context, routing, message, buffer))
        ).onErrorResume(e -> {
            String fallback = fallbackPolicy.fallbackChat(message, CHAT_ANALYSIS_TYPE,
                    FallbackReason.MODEL_UNAVAILABLE).getMessage();
            return Flux.just(
                    event("delta", ShopAIStreamEvent.builder()
                            .type("delta")
                            .text(fallback)
                            .traceId(context.getTraceId())
                            .sessionId(context.getSessionId())
                            .memoryId(context.getMemoryId())
                            .intent(routing == null ? context.getIntent() : routing.getIntent())
                            .routingSource(routing == null ? null : routing.getSource())
                            .routingConfidence(routing == null ? null : routing.getConfidence())
                            .degraded(true)
                            .confidence(0.35)
                            .fallbackReason(FallbackReason.MODEL_UNAVAILABLE.name())
                            .build()),
                    doneEvent(context, routing, true, false, 0.35, null,
                            FallbackReason.MODEL_UNAVAILABLE.name())
            );
        });
    }

    private Flux<ServerSentEvent<ShopAIStreamEvent>> streamAuditedFreeChatText(ShopAIRequestContext context,
                                                                               IntentRoutingResult routing,
                                                                               String message,
                                                                               StreamBuffer buffer) {
        List<String> safeParts = buffer == null ? Collections.emptyList() : buffer.parts();
        AuditResult audit = audit(CHAT_ANALYSIS_TYPE, buffer);
        if (!audit.pass) {
            String fallback = fallbackPolicy.fallbackChat(message, CHAT_ANALYSIS_TYPE,
                    FallbackReason.QUALITY_REJECTED).getMessage();
            String fallbackReason = buffer != null && buffer.isTruncated()
                    ? "OUTPUT_TRUNCATED" : FallbackReason.QUALITY_REJECTED.name();
            return Flux.just(
                    event("delta", ShopAIStreamEvent.builder()
                            .type("delta")
                            .text(fallback)
                            .traceId(context.getTraceId())
                            .sessionId(context.getSessionId())
                            .memoryId(context.getMemoryId())
                            .intent(routing == null ? context.getIntent() : routing.getIntent())
                            .routingSource(routing == null ? null : routing.getSource())
                            .routingConfidence(routing == null ? null : routing.getConfidence())
                            .degraded(true)
                            .confidence(0.35)
                            .fallbackReason(fallbackReason)
                            .build()),
                    auditEvent(context, routing, audit),
                    doneEvent(context, routing, true, false, 0.35, audit,
                            fallbackReason)
            );
        }
        Flux<ServerSentEvent<ShopAIStreamEvent>> deltas = Flux.fromIterable(safeParts)
                .map(part -> event("delta", ShopAIStreamEvent.builder()
                        .type("delta")
                        .text(part)
                        .traceId(context.getTraceId())
                        .sessionId(context.getSessionId())
                        .memoryId(context.getMemoryId())
                        .intent(routing == null ? context.getIntent() : routing.getIntent())
                        .routingSource(routing == null ? null : routing.getSource())
                        .routingConfidence(routing == null ? null : routing.getConfidence())
                        .build()));
        return Flux.concat(
                deltas,
                Flux.just(
                        auditEvent(context, routing, audit),
                        doneEvent(context, routing, false, false, 0.7, audit)
                )
        );
    }

    private Flux<ServerSentEvent<ShopAIStreamEvent>> streamPlan(ShopAIRequestContext context,
                                                                IntentRoutingResult routing,
                                                                StreamWorkflowPlan plan) {
        String memoryId = plan.getMemoryId() == null ? context.getMemoryId() : plan.getMemoryId();
        context.setMemoryId(memoryId);
        if (plan.hasDirectText()) {
            return Flux.concat(
                    Flux.just(metadataEvent(context, routing, memoryId, plan.getPromptVersion()),
                            evidenceEvent(context, routing, memoryId, plan.safeEvidence())),
                    streamAuditedPlanText(context, routing, plan, memoryId,
                            bufferOf(plan.getDirectText()))
            ).filter(item -> item.data() != null);
        }

        return Flux.concat(
                        Flux.just(metadataEvent(context, routing, memoryId, plan.getPromptVersion()),
                                evidenceEvent(context, routing, memoryId, plan.safeEvidence())),
                        streamForPlan(plan)
                                .collect(this::newStreamBuffer, StreamBuffer::append)
                                .flatMapMany(buffer -> streamAuditedPlanText(
                                        context, routing, plan, memoryId, buffer))
                )
                .filter(item -> item.data() != null)
                .onErrorResume(e -> {
                    String fallback = plan.isStructuredOutput()
                            ? structuredFallback(plan, memoryId, FallbackReason.MODEL_UNAVAILABLE)
                            : fallbackPolicy.fallbackText(memoryId, plan.getPrompt(), plan.getAnalysisType());
                    return Flux.just(
                            event("delta", ShopAIStreamEvent.builder()
                                    .type("delta")
                                    .text(fallback)
                                    .traceId(context.getTraceId())
                                    .sessionId(context.getSessionId())
                                    .memoryId(memoryId)
                                    .intent(routing == null ? context.getIntent() : routing.getIntent())
                                    .routingSource(routing == null ? null : routing.getSource())
                                    .routingConfidence(routing == null ? null : routing.getConfidence())
                                    .degraded(true)
                                    .confidence(0.35)
                                    .fallbackReason(FallbackReason.MODEL_UNAVAILABLE.name())
                                    .build()),
                            doneEvent(context, routing, true,
                                    Boolean.TRUE.equals(plan.getCacheHit()),
                                    0.35,
                                    null,
                                    FallbackReason.MODEL_UNAVAILABLE.name())
                    );
                });
    }

    private Flux<ServerSentEvent<ShopAIStreamEvent>> streamAuditedPlanText(ShopAIRequestContext context,
                                                                           IntentRoutingResult routing,
                                                                           StreamWorkflowPlan plan,
                                                                           String memoryId,
                                                                           StreamBuffer buffer) {
        if (plan.isStructuredOutput()) {
            return streamStructuredPlan(context, routing, plan, memoryId, buffer);
        }
        String text = buffer == null ? "" : buffer.text();
        AuditResult audit = audit(plan.getAnalysisType(), buffer);
        String finalText = text;
        boolean degraded = Boolean.TRUE.equals(plan.getDegraded());
        String fallbackReason = null;
        Double confidence = plan.getConfidence();
        if (!audit.pass) {
            finalText = fallbackPolicy.fallbackText(memoryId, plan.getPrompt(), plan.getAnalysisType());
            degraded = true;
            fallbackReason = buffer != null && buffer.isTruncated()
                    ? "OUTPUT_TRUNCATED" : FallbackReason.QUALITY_REJECTED.name();
            confidence = minConfidence(confidence, 0.35);
        }
        return Flux.just(
                event("delta", ShopAIStreamEvent.builder()
                        .type("delta")
                        .text(finalText)
                        .traceId(context.getTraceId())
                        .sessionId(context.getSessionId())
                        .memoryId(memoryId)
                        .intent(routing == null ? context.getIntent() : routing.getIntent())
                        .routingSource(routing == null ? null : routing.getSource())
                        .routingConfidence(routing == null ? null : routing.getConfidence())
                        .degraded(degraded)
                        .confidence(confidence)
                        .fallbackReason(fallbackReason)
                        .build()),
                auditEvent(context, routing, audit),
                doneEvent(context, routing,
                        degraded,
                        Boolean.TRUE.equals(plan.getCacheHit()),
                        confidence,
                        audit,
                        fallbackReason)
        );
    }

    private Flux<ServerSentEvent<ShopAIStreamEvent>> streamStructuredPlan(
            ShopAIRequestContext context,
            IntentRoutingResult routing,
            StreamWorkflowPlan plan,
            String memoryId,
            StreamBuffer buffer) {
        boolean truncated = buffer == null || buffer.isTruncated();
        boolean degraded = Boolean.TRUE.equals(plan.getDegraded());
        String finalText;
        String fallbackReason = null;
        AuditResult audit;
        Double confidence = plan.getConfidence();
        if (truncated) {
            audit = new AuditResult(false, "REJECTED", "STREAM_OUTPUT_TRUNCATED");
            finalText = structuredFallback(plan, memoryId, FallbackReason.QUALITY_REJECTED);
            fallbackReason = "OUTPUT_TRUNCATED";
            degraded = true;
            confidence = minConfidence(confidence, 0.35);
        } else {
            try {
                finalText = normalizeStructured(plan, buffer.text());
                audit = new AuditResult(true, "PASS", null);
            } catch (StructuredOutputRejected rejected) {
                audit = new AuditResult(false, "REJECTED", rejected.reason);
                finalText = structuredFallback(plan, memoryId, FallbackReason.QUALITY_REJECTED);
                fallbackReason = rejected.reasonCode;
                degraded = true;
                confidence = minConfidence(confidence, 0.35);
            } catch (Exception invalid) {
                boolean incomplete = structuredParser().classifyJson(buffer.text())
                        == StructuredOutputParser.JsonStatus.INCOMPLETE;
                String reason = incomplete
                        ? "STRUCTURED_OUTPUT_INCOMPLETE" : "STRUCTURED_OUTPUT_INVALID";
                audit = new AuditResult(false, "REJECTED", reason);
                finalText = structuredFallback(plan, memoryId, FallbackReason.QUALITY_REJECTED);
                fallbackReason = reason;
                degraded = true;
                confidence = minConfidence(confidence, 0.35);
            }
        }
        return Flux.just(
                event("delta", ShopAIStreamEvent.builder()
                        .type("delta")
                        .text(finalText)
                        .traceId(context.getTraceId())
                        .sessionId(context.getSessionId())
                        .memoryId(memoryId)
                        .intent(routing == null ? context.getIntent() : routing.getIntent())
                        .routingSource(routing == null ? null : routing.getSource())
                        .routingConfidence(routing == null ? null : routing.getConfidence())
                        .degraded(degraded)
                        .confidence(confidence)
                        .fallbackReason(fallbackReason)
                        .build()),
                auditEvent(context, routing, audit),
                doneEvent(context, routing, degraded,
                        Boolean.TRUE.equals(plan.getCacheHit()), confidence, audit, fallbackReason));
    }

    private String normalizeStructured(StreamWorkflowPlan plan, String raw) throws Exception {
        String type = plan.getAnalysisType();
        if (isQaAnalysisType(type)) {
            ShopQAResult result = structuredParser().parseQA(raw, plan.getExpectedShopId(), plan.getExpectedQuestion());
            QualityCheck check = qualityGuard.validateQA(result, plan.getExpectedShopId(), plan.safeEvidence(), type);
            ensureStructuredQuality(check);
            return mapper().writeValueAsString(result);
        }
        if ("compare".equals(type)) {
            ShopCompareResult result = structuredParser().parseCompare(raw, plan.getExpectedShopId1(),
                    plan.getExpectedShopId2(), plan.getExpectedAspect());
            QualityCheck check = qualityGuard.validateCompare(result, plan.getExpectedShopId1(),
                    plan.getExpectedShopId2(), plan.safeEvidence(), type);
            ensureStructuredQuality(check);
            return mapper().writeValueAsString(result);
        }
        if ("recommend".equals(type)) {
            ShopRecommendResult result = structuredParser().parseRecommend(raw,
                    plan.getExpectedUserPreference(), plan.getExpectedCategory(), plan.getCandidateShops());
            QualityCheck check = qualityGuard.validateRecommend(result,
                    plan.getCandidateShopIds() == null ? Collections.emptySet() : plan.getCandidateShopIds(),
                    plan.safeEvidence(), type);
            ensureStructuredQuality(check);
            limitRecommendation(result, plan.getRequestedLimit());
            return mapper().writeValueAsString(result);
        }
        throw new StructuredOutputRejected("STRUCTURED_OUTPUT_TYPE_UNSUPPORTED", "STRUCTURED_OUTPUT_INVALID");
    }

    private void ensureStructuredQuality(QualityCheck check) throws StructuredOutputRejected {
        if (check == null || !check.pass()) {
            String reason = check == null || isBlank(check.getReason())
                    ? "STRUCTURED_OUTPUT_REJECTED" : check.getReason();
            throw new StructuredOutputRejected(reason, "STRUCTURED_OUTPUT_REJECTED");
        }
    }

    private String structuredFallback(StreamWorkflowPlan plan, String memoryId, FallbackReason reason) {
        try {
            if (isQaAnalysisType(plan.getAnalysisType())) {
                return mapper().writeValueAsString(fallbackPolicy.fallbackQA(plan.getExpectedShopId(),
                        plan.getExpectedQuestion(), plan.getAnalysisType(), reason));
            }
            if ("compare".equals(plan.getAnalysisType())) {
                return mapper().writeValueAsString(fallbackPolicy.fallbackCompare(plan.getExpectedShopId1(),
                        plan.getExpectedShopId2(), plan.getExpectedAspect(), plan.getAnalysisType(),
                        reason));
            }
            if ("recommend".equals(plan.getAnalysisType())) {
                int limit = plan.getRequestedLimit() == null ? 5 : plan.getRequestedLimit();
                return mapper().writeValueAsString(fallbackPolicy.fallbackRecommend(
                        plan.getExpectedUserPreference(), plan.getExpectedCategory(), plan.getCandidateShops(),
                        limit, plan.getAnalysisType(), reason));
            }
        } catch (Exception ignored) {
            // Fall through to the existing text fallback if a typed fallback cannot be serialized.
        }
        return fallbackPolicy.fallbackText(memoryId, plan.getPrompt(), plan.getAnalysisType());
    }

    private boolean isQaAnalysisType(String analysisType) {
        return "ask".equals(analysisType) || "qa".equals(analysisType);
    }

    private void limitRecommendation(ShopRecommendResult result, Integer requestedLimit) {
        if (result == null || requestedLimit == null || result.getItems() == null
                || result.getItems().size() <= requestedLimit) return;
        List<ShopRecommendationItem> limited = new ArrayList<>(
                result.getItems().subList(0, Math.max(1, requestedLimit)));
        for (int i = 0; i < limited.size(); i++) limited.get(i).setRank(i + 1);
        result.setItems(limited);
    }

    private Flux<String> streamForPlan(StreamWorkflowPlan plan) {
        if ("compare".equals(plan.getAnalysisType())) {
            return modelGateway.streamComparison(plan.getMemoryId(), plan.getPrompt());
        }
        if ("recommend".equals(plan.getAnalysisType())) {
            return modelGateway.streamRecommendation(plan.getMemoryId(), plan.getPrompt());
        }
        return modelGateway.streamAnswer(plan.getMemoryId(), plan.getPrompt());
    }

    private Flux<ServerSentEvent<ShopAIStreamEvent>> streamResponse(ShopAIRequestContext context,
                                                                    IntentRoutingResult routing,
                                                                    ShopAIResponse response) {
        String memoryId = response.getMemoryId() == null ? context.getMemoryId() : response.getMemoryId();
        Flux<ServerSentEvent<ShopAIStreamEvent>> base = Flux.concat(
                Flux.just(
                        metadataEvent(context, routing, memoryId, response.getPromptVersion()),
                        evidenceEvent(context, routing, memoryId, safeEvidence(response.getEvidence()))),
                streamAuditedResponseText(context, routing, response, memoryId)
        );
        return base.filter(item -> item.data() != null);
    }

    private Flux<ServerSentEvent<ShopAIStreamEvent>> streamAuditedResponseText(ShopAIRequestContext context,
                                                                               IntentRoutingResult routing,
                                                                               ShopAIResponse response,
                                                                               String memoryId) {
        String text = responseText(response);
        String analysisType = responseAnalysisType(response);
        AuditResult audit = audit(analysisType, text);
        String finalText = text;
        boolean degraded = Boolean.TRUE.equals(response.getDegraded());
        String fallbackReason = response.getFallbackReason();
        Double confidence = response.getConfidence();
        if (!audit.pass) {
            finalText = fallbackPolicy.fallbackText(memoryId, "", analysisType);
            degraded = true;
            fallbackReason = FallbackReason.QUALITY_REJECTED.name();
            confidence = minConfidence(confidence, 0.35);
        }
        return Flux.just(
                event("delta", ShopAIStreamEvent.builder()
                        .type("delta")
                        .text(finalText)
                        .traceId(context.getTraceId())
                        .sessionId(context.getSessionId())
                        .memoryId(memoryId)
                        .intent(routing == null ? context.getIntent() : routing.getIntent())
                        .routingSource(routing == null ? null : routing.getSource())
                        .routingConfidence(routing == null ? null : routing.getConfidence())
                        .degraded(degraded)
                        .confidence(confidence)
                        .fallbackReason(fallbackReason)
                        .build()),
                auditEvent(context, routing, audit),
                doneEvent(context, routing,
                        degraded,
                        Boolean.TRUE.equals(response.getCacheHit()),
                        confidence,
                        audit,
                        fallbackReason)
        );
    }

    private Flux<ServerSentEvent<ShopAIStreamEvent>> streamText(ShopAIRequestContext context,
                                                               IntentRoutingResult routing,
                                                               String text,
                                                               boolean degraded,
                                                               double confidence) {
        String memoryId = context.getMemoryId();
        return Flux.just(
                metadataEvent(context, routing, memoryId),
                event("delta", ShopAIStreamEvent.builder()
                        .type("delta")
                        .text(text)
                        .traceId(context.getTraceId())
                        .sessionId(context.getSessionId())
                        .memoryId(memoryId)
                        .intent(routing == null ? context.getIntent() : routing.getIntent())
                        .routingSource(routing == null ? null : routing.getSource())
                        .routingConfidence(routing == null ? null : routing.getConfidence())
                        .degraded(degraded)
                        .confidence(confidence)
                        .build()),
                doneEvent(context, routing, degraded, false, confidence)
        );
    }

    private ServerSentEvent<ShopAIStreamEvent> metadataEvent(ShopAIRequestContext context,
                                                            IntentRoutingResult routing,
                                                            String memoryId) {
        return metadataEvent(context, routing, memoryId, null);
    }

    private ServerSentEvent<ShopAIStreamEvent> metadataEvent(ShopAIRequestContext context,
                                                            IntentRoutingResult routing,
                                                            String memoryId,
                                                            String promptVersion) {
        return event("metadata", ShopAIStreamEvent.builder()
                .type("metadata")
                .traceId(context.getTraceId())
                .sessionId(context.getSessionId())
                .memoryId(memoryId)
                .promptVersion(promptVersion)
                .intent(routing == null ? context.getIntent() : routing.getIntent())
                .routingSource(routing == null ? null : routing.getSource())
                .routingConfidence(routing == null ? null : routing.getConfidence())
                .build());
    }

    private ServerSentEvent<ShopAIStreamEvent> evidenceEvent(ShopAIRequestContext context,
                                                            IntentRoutingResult routing,
                                                            String memoryId,
                                                            List<EvidenceItem> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return ServerSentEvent.<ShopAIStreamEvent>builder().event("evidence").build();
        }
        return event("evidence", ShopAIStreamEvent.builder()
                .type("evidence")
                .traceId(context.getTraceId())
                .sessionId(context.getSessionId())
                .memoryId(memoryId)
                .intent(routing == null ? context.getIntent() : routing.getIntent())
                .routingSource(routing == null ? null : routing.getSource())
                .routingConfidence(routing == null ? null : routing.getConfidence())
                .evidence(evidence)
                .build());
    }

    private ServerSentEvent<ShopAIStreamEvent> doneEvent(ShopAIRequestContext context,
                                                        IntentRoutingResult routing,
                                                        boolean degraded,
                                                        boolean cacheHit,
                                                        Double confidence) {
        return doneEvent(context, routing, degraded, cacheHit, confidence, null, null);
    }

    private ServerSentEvent<ShopAIStreamEvent> doneEvent(ShopAIRequestContext context,
                                                        IntentRoutingResult routing,
                                                        boolean degraded,
                                                        boolean cacheHit,
                                                        Double confidence,
                                                        AuditResult audit) {
        return doneEvent(context, routing, degraded, cacheHit, confidence, audit, null);
    }

    private ServerSentEvent<ShopAIStreamEvent> doneEvent(ShopAIRequestContext context,
                                                        IntentRoutingResult routing,
                                                        boolean degraded,
                                                        boolean cacheHit,
                                                        Double confidence,
                                                        AuditResult audit,
                                                        String fallbackReason) {
        boolean auditRejected = audit != null && !audit.pass;
        Double finalConfidence = auditRejected ? minConfidence(confidence, 0.35) : confidence;
        String finalFallbackReason = auditRejected && isBlank(fallbackReason)
                ? FallbackReason.QUALITY_REJECTED.name() : fallbackReason;
        return event("done", ShopAIStreamEvent.builder()
                .type("done")
                .traceId(context.getTraceId())
                .sessionId(context.getSessionId())
                .memoryId(context.getMemoryId())
                .intent(routing == null ? context.getIntent() : routing.getIntent())
                .routingSource(routing == null ? null : routing.getSource())
                .routingConfidence(routing == null ? null : routing.getConfidence())
                .degraded(degraded || auditRejected)
                .cacheHit(cacheHit)
                .confidence(finalConfidence)
                .auditStatus(audit == null ? null : audit.status)
                .auditReason(audit == null ? null : audit.reason)
                .fallbackReason(finalFallbackReason)
                .build());
    }

    private ServerSentEvent<ShopAIStreamEvent> auditEvent(ShopAIRequestContext context,
                                                         IntentRoutingResult routing,
                                                         AuditResult audit) {
        return event("audit", ShopAIStreamEvent.builder()
                .type("audit")
                .traceId(context.getTraceId())
                .sessionId(context.getSessionId())
                .memoryId(context.getMemoryId())
                .intent(routing == null ? context.getIntent() : routing.getIntent())
                .routingSource(routing == null ? null : routing.getSource())
                .routingConfidence(routing == null ? null : routing.getConfidence())
                .auditStatus(audit.status)
                .auditReason(audit.reason)
                .build());
    }

    private AuditResult audit(String analysisType, String text) {
        QualityCheck quality = qualityGuard.validateText(text, analysisType);
        boolean pass = quality.pass();
        return new AuditResult(pass, pass ? "PASS" : "REJECTED", pass ? null : quality.getReason());
    }

    private AuditResult audit(String analysisType, StreamBuffer buffer) {
        if (buffer == null || buffer.isTruncated()) {
            return new AuditResult(false, "REJECTED", "STREAM_OUTPUT_TRUNCATED");
        }
        return audit(analysisType, buffer.text());
    }

    private StreamBuffer newStreamBuffer() {
        return new StreamBuffer(Math.max(1, maxStreamResponseChars), MAX_STREAM_PARTS);
    }

    private StreamBuffer bufferOf(String value) {
        StreamBuffer buffer = newStreamBuffer();
        buffer.append(value);
        return buffer;
    }

    private StructuredOutputParser structuredParser() {
        if (structuredOutputParser == null) structuredOutputParser = new StructuredOutputParser();
        return structuredOutputParser;
    }

    private ObjectMapper mapper() {
        if (objectMapper == null) objectMapper = new ObjectMapper();
        return objectMapper;
    }

    private Double minConfidence(Double confidence, double max) {
        if (confidence == null) {
            return max;
        }
        return Math.min(confidence, max);
    }

    private ServerSentEvent<ShopAIStreamEvent> event(String event, ShopAIStreamEvent data) {
        return ServerSentEvent.<ShopAIStreamEvent>builder()
                .event(event)
                .data(data)
                .build();
    }

    private ShopAIResponse withRouting(ShopAIResponse response, IntentRoutingResult routing) {
        if (response == null || routing == null) {
            return response;
        }
        response.setIntent(routing.getIntent());
        response.setRoutingSource(routing.getSource());
        response.setRoutingConfidence(routing.getConfidence());
        return response;
    }

    private List<EvidenceItem> safeEvidence(List<EvidenceItem> evidence) {
        return evidence == null ? Collections.emptyList() : evidence;
    }

    private String responseText(ShopAIResponse response) {
        if (response == null) {
            return "";
        }
        if (response.getSummary() != null) {
            return nullSafe(response.getSummary().getCoreSummary());
        }
        if (response.getQa() != null) {
            return nullSafe(response.getQa().getAnswer());
        }
        if (response.getCompare() != null) {
            return nullSafe(response.getCompare().getConclusion());
        }
        if (response.getRecommend() != null) {
            if (!isBlank(response.getRecommend().getMessage())) {
                return response.getRecommend().getMessage();
            }
            return response.getRecommend().safeItems().stream()
                    .map(item -> item.getRank() + ". " + item.getShopName() + ": " + item.getReason())
                    .collect(Collectors.joining("\n"));
        }
        if (response.getChat() != null) {
            return nullSafe(response.getChat().getMessage());
        }
        return "";
    }

    private String responseAnalysisType(ShopAIResponse response) {
        if (response == null) {
            return CHAT_ANALYSIS_TYPE;
        }
        if (response.getSummary() != null) {
            return "summary";
        }
        if (response.getQa() != null) {
            return "qa";
        }
        if (response.getCompare() != null) {
            return "compare";
        }
        if (response.getRecommend() != null) {
            return "recommend";
        }
        return CHAT_ANALYSIS_TYPE;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class StreamBuffer {
        private final int maxChars;
        private final int maxParts;
        private final StringBuilder text = new StringBuilder();
        private final List<String> parts = new ArrayList<>();
        private boolean truncated;

        private StreamBuffer(int maxChars, int maxParts) {
            this.maxChars = maxChars;
            this.maxParts = maxParts;
        }

        private void append(String value) {
            if (truncated) return;
            String part = value == null ? "" : value;
            if (parts.size() >= maxParts || part.length() > maxChars - text.length()) {
                truncated = true;
                return;
            }
            parts.add(part);
            text.append(part);
        }

        private String text() {
            return text.toString();
        }

        private List<String> parts() {
            return Collections.unmodifiableList(parts);
        }

        private boolean isTruncated() {
            return truncated;
        }
    }

    private static final class StructuredOutputRejected extends Exception {
        private final String reason;
        private final String reasonCode;

        private StructuredOutputRejected(String reason, String reasonCode) {
            super(reason);
            this.reason = reason;
            this.reasonCode = reasonCode;
        }
    }

    private static class AuditResult {
        private final boolean pass;
        private final String status;
        private final String reason;

        private AuditResult(boolean pass, String status, String reason) {
            this.pass = pass;
            this.status = status;
            this.reason = reason;
        }
    }
}
