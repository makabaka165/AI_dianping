package com.hmdp.ai.workflow;

import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.intent.IntentRouteCoordinator;
import com.hmdp.ai.intent.IntentRoutingResult;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
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
import com.hmdp.entity.ShopSummaryResult;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ChatWorkflow implements ShopAIWorkflow<ChatWorkflowRequest, ShopAIResponse> {

    private static final String CHAT_ANALYSIS_TYPE = "chat";
    private static final String CHAT_MODEL_OPERATION = "chat:freeChat";
    private static final String STREAM_MODEL_OPERATION = "chat:streamChat";

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
            fallbackPolicy.recordFailure(STREAM_MODEL_OPERATION);
            PromptTemplateRender prompt = promptTemplateRegistry.renderFreeChat(context, request.getMessage());
            return streamText(context, routing, fallbackPolicy.fallbackText(context.getMemoryId(),
                    prompt.getContent(), CHAT_ANALYSIS_TYPE), true, 0.35);
        }
    }

    private ShopAIResponse freeChat(ShopAIRequestContext context, String message) {
        PromptTemplateRender prompt = promptTemplateRegistry.renderFreeChat(context, message);
        boolean degraded = false;
        ShopChatResult chat;
        if (fallbackPolicy.shouldUseFallback(CHAT_MODEL_OPERATION)) {
            chat = fallbackPolicy.fallbackChat(message, CHAT_ANALYSIS_TYPE);
            degraded = true;
        } else {
            try {
                String answer = modelGateway.generateFreeChat(context.getMemoryId(), prompt.getContent());
                QualityCheck quality = qualityGuard.validateText(answer, CHAT_ANALYSIS_TYPE);
                if (!quality.pass()) {
                    answer = modelGateway.repairFreeChat(context.getMemoryId(), prompt.getContent(), quality.getReason());
                    quality = qualityGuard.validateText(answer, CHAT_ANALYSIS_TYPE);
                    if (!quality.pass()) {
                        chat = fallbackPolicy.fallbackChat(message, CHAT_ANALYSIS_TYPE);
                        degraded = true;
                    } else {
                        chat = ShopChatResult.builder()
                                .message(qualityGuard.postProcess(answer))
                                .clarification(false)
                                .build();
                    }
                } else {
                    chat = ShopChatResult.builder()
                            .message(qualityGuard.postProcess(answer))
                            .clarification(false)
                            .build();
                }
            } catch (Exception e) {
                fallbackPolicy.recordFailure(CHAT_MODEL_OPERATION);
                chat = fallbackPolicy.fallbackChat(message, CHAT_ANALYSIS_TYPE);
                degraded = true;
            }
        }
        ShopAIResponse response = chatResponse(context, chat.getMessage(), degraded, Boolean.TRUE.equals(chat.getClarification()));
        response.setPromptVersion(prompt.getVersion());
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
        if (fallbackPolicy.shouldUseFallback(CHAT_MODEL_OPERATION)) {
            return streamText(context, routing, fallbackPolicy.fallbackChat(message, CHAT_ANALYSIS_TYPE).getMessage(), true, 0.35);
        }
        Flux<ServerSentEvent<ShopAIStreamEvent>> chunks = modelGateway.streamChat(context.getMemoryId(), prompt.getContent())
                .map(text -> event("delta", ShopAIStreamEvent.builder()
                        .type("delta")
                        .text(text)
                        .traceId(context.getTraceId())
                        .sessionId(context.getSessionId())
                        .memoryId(context.getMemoryId())
                        .intent(routing == null ? context.getIntent() : routing.getIntent())
                        .routingSource(routing == null ? null : routing.getSource())
                        .routingConfidence(routing == null ? null : routing.getConfidence())
                        .build()));
        return Flux.concat(
                Flux.just(metadataEvent(context, routing, context.getMemoryId(), prompt.getVersion())),
                chunks,
                Flux.just(doneEvent(context, routing, false, false, 0.7))
        ).onErrorResume(e -> {
            fallbackPolicy.recordFailure(CHAT_MODEL_OPERATION);
            String fallback = fallbackPolicy.fallbackChat(message, CHAT_ANALYSIS_TYPE).getMessage();
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
                            .build()),
                    doneEvent(context, routing, true, false, 0.35)
            );
        });
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
                    Flux.just(event("delta", ShopAIStreamEvent.builder()
                            .type("delta")
                            .text(plan.getDirectText())
                            .traceId(context.getTraceId())
                            .sessionId(context.getSessionId())
                            .memoryId(memoryId)
                            .intent(routing == null ? context.getIntent() : routing.getIntent())
                            .routingSource(routing == null ? null : routing.getSource())
                            .routingConfidence(routing == null ? null : routing.getConfidence())
                            .degraded(Boolean.TRUE.equals(plan.getDegraded()))
                            .confidence(plan.getConfidence())
                            .build())),
                    Flux.just(doneEvent(context, routing,
                            Boolean.TRUE.equals(plan.getDegraded()),
                            Boolean.TRUE.equals(plan.getCacheHit()),
                            plan.getConfidence()))
            ).filter(item -> item.data() != null);
        }

        Flux<ServerSentEvent<ShopAIStreamEvent>> chunks = streamForPlan(plan)
                .map(text -> event("delta", ShopAIStreamEvent.builder()
                        .type("delta")
                        .text(text)
                        .traceId(context.getTraceId())
                        .sessionId(context.getSessionId())
                        .memoryId(memoryId)
                        .intent(routing == null ? context.getIntent() : routing.getIntent())
                        .routingSource(routing == null ? null : routing.getSource())
                        .routingConfidence(routing == null ? null : routing.getConfidence())
                        .build()));

        return Flux.concat(
                        Flux.just(metadataEvent(context, routing, memoryId, plan.getPromptVersion()),
                                evidenceEvent(context, routing, memoryId, plan.safeEvidence())),
                        chunks,
                        Flux.just(doneEvent(context, routing, false,
                                Boolean.TRUE.equals(plan.getCacheHit()),
                                plan.getConfidence()))
                )
                .filter(item -> item.data() != null)
                .onErrorResume(e -> {
                    fallbackPolicy.recordFailure("stream:" + plan.getAnalysisType());
                    String fallback = fallbackPolicy.fallbackText(memoryId, plan.getPrompt(), plan.getAnalysisType());
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
                                    .build()),
                            doneEvent(context, routing, true,
                                    Boolean.TRUE.equals(plan.getCacheHit()),
                                    0.35)
                    );
                });
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
        Flux<ServerSentEvent<ShopAIStreamEvent>> base = Flux.just(
                metadataEvent(context, routing, memoryId, response.getPromptVersion()),
                evidenceEvent(context, routing, memoryId, safeEvidence(response.getEvidence())),
                event("delta", ShopAIStreamEvent.builder()
                        .type("delta")
                        .text(responseText(response))
                        .traceId(context.getTraceId())
                        .sessionId(context.getSessionId())
                        .memoryId(memoryId)
                        .intent(routing == null ? context.getIntent() : routing.getIntent())
                        .routingSource(routing == null ? null : routing.getSource())
                        .routingConfidence(routing == null ? null : routing.getConfidence())
                        .build()),
                doneEvent(context, routing,
                        Boolean.TRUE.equals(response.getDegraded()),
                        Boolean.TRUE.equals(response.getCacheHit()),
                        response.getConfidence())
        );
        return base.filter(item -> item.data() != null);
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
        return event("done", ShopAIStreamEvent.builder()
                .type("done")
                .traceId(context.getTraceId())
                .sessionId(context.getSessionId())
                .memoryId(context.getMemoryId())
                .intent(routing == null ? context.getIntent() : routing.getIntent())
                .routingSource(routing == null ? null : routing.getSource())
                .routingConfidence(routing == null ? null : routing.getConfidence())
                .degraded(degraded)
                .cacheHit(cacheHit)
                .confidence(confidence)
                .build());
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

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
