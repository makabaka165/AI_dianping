package com.hmdp.ai.workflow;

import com.hmdp.ai.intent.IntentRouteCoordinator;
import com.hmdp.dto.ai.IntentRouteSource;
import com.hmdp.dto.ai.IntentRoutingResult;
import com.hmdp.dto.ai.ShopAIIntent;
import com.hmdp.ai.fallback.FallbackPolicy;
import com.hmdp.ai.fallback.FallbackReason;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityDecision;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.ChatWorkflowRequest;
import com.hmdp.ai.workflow.request.QAWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAIStreamEvent;
import com.hmdp.dto.ai.ShopSummaryResult;
import com.hmdp.dto.ai.ShopQAResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ChatWorkflowStreamTest {

    @Mock
    private IntentRouteCoordinator intentRouteCoordinator;

    @Mock
    private MemoryService memoryService;

    @Mock
    private SummaryWorkflow summaryWorkflow;

    @Mock
    private QAWorkflow qaWorkflow;

    @Mock
    private ModelGateway modelGateway;

    @Mock
    private QualityGuard qualityGuard;

    @Mock
    private FallbackPolicy fallbackPolicy;

    private ChatWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new ChatWorkflow();
        ReflectionTestUtils.setField(workflow, "intentRouteCoordinator", intentRouteCoordinator);
        ReflectionTestUtils.setField(workflow, "memoryService", memoryService);
        ReflectionTestUtils.setField(workflow, "summaryWorkflow", summaryWorkflow);
        ReflectionTestUtils.setField(workflow, "qaWorkflow", qaWorkflow);
        ReflectionTestUtils.setField(workflow, "modelGateway", modelGateway);
        ReflectionTestUtils.setField(workflow, "qualityGuard", qualityGuard);
        ReflectionTestUtils.setField(workflow, "fallbackPolicy", fallbackPolicy);
        ReflectionTestUtils.setField(workflow, "promptTemplateRegistry", new PromptTemplateRegistry());
        lenient().when(fallbackPolicy.fallbackChat(any(), eq("chat"), eq(com.hmdp.ai.fallback.FallbackReason.QUALITY_REJECTED)))
                .thenReturn(com.hmdp.dto.ai.ShopChatResult.builder()
                        .message("free chat fallback")
                        .clarification(false)
                        .build());
    }

    @Test
    void shouldEmitStructuredEventsForClarification() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("m1");
        when(intentRouteCoordinator.route(context, "这家店服务怎么样", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.QA)
                .source(IntentRouteSource.CLARIFICATION)
                .confidence(0.9)
                .clarification("请提供店铺ID")
                .build());

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context, ChatWorkflowRequest.builder()
                        .message("这家店服务怎么样")
                        .build())
                .collectList()
                .block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0).event()).isEqualTo("metadata");
        assertThat(events.get(1).event()).isEqualTo("delta");
        assertThat(events.get(1).data().getText()).isEqualTo("请提供店铺ID");
        assertThat(events.get(2).event()).isEqualTo("done");
        verify(intentRouteCoordinator).route(context, "这家店服务怎么样", null);
    }

    @Test
    void shouldPassExplicitShopIdToStreamRouter() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("m1");
        when(intentRouteCoordinator.route(context, "service?", 12L)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.QA)
                .source(IntentRouteSource.CLARIFICATION)
                .confidence(0.9)
                .clarification("need more info")
                .build());

        workflow.stream(context, ChatWorkflowRequest.builder()
                        .message("service?")
                        .shopId(12L)
                        .build())
                .collectList()
                .block();

        verify(intentRouteCoordinator).route(context, "service?", 12L);
    }

    @Test
    void shouldRouteSummaryThroughWorkflowAndKeepChatMemoryMetadata() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("chat-memory");
        when(intentRouteCoordinator.route(context, "summary shop 1", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.SUMMARY)
                .source(IntentRouteSource.RULE)
                .confidence(0.95)
                .shopId(1L)
                .build());
        when(summaryWorkflow.execute(eq(context), any())).thenReturn(ShopSummaryResult.builder()
                .shopId(1L)
                .coreSummary("summary")
                .memoryId("chat-memory")
                .traceId("t1")
                .confidence(0.8)
                .degraded(false)
                .cacheHit(false)
                .build());

        ShopAIResponse response = workflow.execute(context, ChatWorkflowRequest.builder()
                .message("summary shop 1")
                .build());

        assertThat(response.getSummary().getCoreSummary()).isEqualTo("summary");
        assertThat(response.getMemoryId()).isEqualTo("chat-memory");
        assertThat(response.getIntent()).isEqualTo(ShopAIIntent.SUMMARY);
        verify(summaryWorkflow).execute(eq(context), any());
    }

    @Test
    void summaryStreamAuditRejectedShouldEmitFallbackDeltaAndDegradedDone() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        String unsafeSummary = "raw user review with banned-phone-12345";
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("chat-memory");
        when(intentRouteCoordinator.route(context, "summary shop 1", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.SUMMARY)
                .source(IntentRouteSource.RULE)
                .confidence(0.95)
                .shopId(1L)
                .build());
        when(summaryWorkflow.execute(eq(context), any())).thenReturn(ShopSummaryResult.builder()
                .shopId(1L)
                .coreSummary(unsafeSummary)
                .memoryId("chat-memory")
                .traceId("t1")
                .confidence(0.8)
                .degraded(false)
                .cacheHit(false)
                .build());
        when(qualityGuard.validateText(unsafeSummary, "summary"))
                .thenReturn(QualityCheck.builder()
                        .decision(QualityDecision.FALLBACK)
                        .reason("unsafe summary")
                        .build());
        when(fallbackPolicy.fallbackText("chat-memory", "", "summary"))
                .thenReturn("safe summary fallback");

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context, ChatWorkflowRequest.builder()
                        .message("summary shop 1")
                        .build())
                .collectList()
                .block();

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("metadata", "delta", "audit", "done");
        ShopAIStreamEvent delta = events.get(1).data();
        ShopAIStreamEvent audit = events.get(2).data();
        ShopAIStreamEvent done = events.get(3).data();
        assertThat(delta.getText())
                .isEqualTo("safe summary fallback")
                .doesNotContain(unsafeSummary);
        assertThat(delta.getDegraded()).isTrue();
        assertThat(delta.getFallbackReason()).isEqualTo("QUALITY_REJECTED");
        assertThat(audit.getAuditStatus()).isEqualTo("REJECTED");
        assertThat(audit.getAuditReason()).isEqualTo("unsafe summary");
        assertThat(done.getDegraded()).isTrue();
        assertThat(done.getAuditStatus()).isEqualTo("REJECTED");
        assertThat(done.getFallbackReason()).isEqualTo("QUALITY_REJECTED");
        assertThat(done.getConfidence()).isLessThanOrEqualTo(0.35);
        verify(qualityGuard).validateText(unsafeSummary, "summary");
        verify(fallbackPolicy).fallbackText("chat-memory", "", "summary");
    }

    @Test
    void freeChatStreamShouldEmitAuditEventAfterDeltas() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("m1");
        when(intentRouteCoordinator.route(context, "hello", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.FREE_CHAT)
                .source(IntentRouteSource.RULE)
                .confidence(0.3)
                .build());
        when(modelGateway.streamChat(eq("m1"), any())).thenReturn(reactor.core.publisher.Flux.just("你好", "，请提供店铺ID"));
        when(qualityGuard.validateText("你好，请提供店铺ID", "chat"))
                .thenReturn(QualityCheck.builder().decision(QualityDecision.PASS).build());

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context, ChatWorkflowRequest.builder()
                        .message("hello")
                        .build())
                .collectList()
                .block();

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("metadata", "delta", "delta", "audit", "done");
        ShopAIStreamEvent audit = events.get(3).data();
        assertThat(audit.getAuditStatus()).isEqualTo("PASS");
    }

    @Test
    void freeChatStreamAuditRejectedShouldMarkDoneDegraded() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("m1");
        when(intentRouteCoordinator.route(context, "hello", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.FREE_CHAT)
                .source(IntentRouteSource.RULE)
                .confidence(0.3)
                .build());
        when(modelGateway.streamChat(eq("m1"), any())).thenReturn(reactor.core.publisher.Flux.just("我是AI，", "无法判断"));
        when(qualityGuard.validateText("我是AI，无法判断", "chat"))
                .thenReturn(QualityCheck.builder()
                        .decision(QualityDecision.FALLBACK)
                        .reason("内容包含模型自我引用")
                        .build());

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context, ChatWorkflowRequest.builder()
                        .message("hello")
                        .build())
                .collectList()
                .block();

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("metadata", "delta", "audit", "done");
        assertThat(events.get(1).data().getText())
                .isEqualTo("free chat fallback")
                .doesNotContain("AI");
        ShopAIStreamEvent audit = events.get(2).data();
        ShopAIStreamEvent done = events.get(3).data();
        assertThat(audit.getAuditStatus()).isEqualTo("REJECTED");
        assertThat(done.getDegraded()).isTrue();
        assertThat(done.getConfidence()).isLessThanOrEqualTo(0.35);
        assertThat(done.getAuditStatus()).isEqualTo("REJECTED");
        assertThat(done.getFallbackReason()).isEqualTo("QUALITY_REJECTED");
    }

    @Test
    void streamPlanAuditRejectedShouldMarkDoneDegraded() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("chat-memory");
        when(intentRouteCoordinator.route(context, "店铺1服务怎么样", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.QA)
                .source(IntentRouteSource.RULE)
                .confidence(0.9)
                .shopId(1L)
                .build());
        when(qaWorkflow.prepareStreamPlan(eq(context), any(QAWorkflowRequest.class))).thenReturn(StreamWorkflowPlan.builder()
                .analysisType("qa")
                .memoryId("qa-memory")
                .prompt("prompt")
                .promptVersion("shop-qa-v3")
                .confidence(0.8)
                .cacheHit(false)
                .build());
        when(modelGateway.streamAnswer("qa-memory", "prompt"))
                .thenReturn(reactor.core.publisher.Flux.just("我保证", "这家店绝对最好"));
        when(qualityGuard.validateText("我保证这家店绝对最好", "qa"))
                .thenReturn(QualityCheck.builder()
                        .decision(QualityDecision.FALLBACK)
                        .reason("内容包含过度确定或越界承诺")
                        .build());
        when(fallbackPolicy.fallbackText("qa-memory", "prompt", "qa"))
                .thenReturn("当前无法提供可靠回答，请稍后再试。");

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context, ChatWorkflowRequest.builder()
                        .message("店铺1服务怎么样")
                        .build())
                .collectList()
                .block();

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("metadata", "delta", "audit", "done");
        assertThat(events.get(1).data().getText()).isEqualTo("当前无法提供可靠回答，请稍后再试。");
        assertThat(events.get(1).data().getText()).doesNotContain("我保证", "绝对最好");
        assertThat(events.get(2).data().getAuditStatus()).isEqualTo("REJECTED");
        assertThat(events.get(3).data().getDegraded()).isTrue();
        assertThat(events.get(3).data().getAuditStatus()).isEqualTo("REJECTED");
        assertThat(events.get(3).data().getConfidence()).isLessThanOrEqualTo(0.35);
    }

    @Test
    void auditPassShouldKeepDoneNonDegraded() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("m1");
        when(intentRouteCoordinator.route(context, "hello", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.FREE_CHAT)
                .source(IntentRouteSource.RULE)
                .confidence(0.3)
                .build());
        when(modelGateway.streamChat(eq("m1"), any())).thenReturn(reactor.core.publisher.Flux.just("请提供店铺ID"));
        when(qualityGuard.validateText("请提供店铺ID", "chat"))
                .thenReturn(QualityCheck.builder().decision(QualityDecision.PASS).build());

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context, ChatWorkflowRequest.builder()
                        .message("hello")
                        .build())
                .collectList()
                .block();

        ShopAIStreamEvent done = events.get(events.size() - 1).data();
        assertThat(done.getDegraded()).isFalse();
        assertThat(done.getAuditStatus()).isEqualTo("PASS");
    }

    @Test
    void errorFallbackShouldMarkDoneDegradedWithoutAuditPass() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1")
                .sessionId("s1")
                .traceId("t1")
                .build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("m1");
        when(intentRouteCoordinator.route(context, "hello", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.FREE_CHAT)
                .source(IntentRouteSource.RULE)
                .confidence(0.3)
                .build());
        when(modelGateway.streamChat(eq("m1"), any())).thenReturn(reactor.core.publisher.Flux.error(new RuntimeException("boom")));
        when(fallbackPolicy.fallbackChat(eq("hello"), eq("chat"), any()))
                .thenReturn(com.hmdp.dto.ai.ShopChatResult.builder()
                        .message("当前 AI 服务不可用")
                        .clarification(false)
                        .build());

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context, ChatWorkflowRequest.builder()
                        .message("hello")
                        .build())
                .collectList()
                .block();

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("metadata", "delta", "done");
        ShopAIStreamEvent done = events.get(2).data();
        assertThat(done.getDegraded()).isTrue();
        assertThat(done.getAuditStatus()).isNull();
        assertThat(done.getFallbackReason()).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test
    void freeChatStreamShouldFallbackWhenBoundedBufferTruncatesOutput() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1").sessionId("s1").traceId("t1").build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("m1");
        when(intentRouteCoordinator.route(context, "hello", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.FREE_CHAT).source(IntentRouteSource.RULE).confidence(0.3).build());
        when(modelGateway.streamChat(eq("m1"), any())).thenReturn(reactor.core.publisher.Flux.just("12345", "67890"));
        ReflectionTestUtils.setField(workflow, "maxStreamResponseChars", 8);

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context,
                        ChatWorkflowRequest.builder().message("hello").build())
                .collectList().block();

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("metadata", "delta", "audit", "done");
        assertThat(events.get(1).data().getFallbackReason()).isEqualTo("OUTPUT_TRUNCATED");
        assertThat(events.get(2).data().getAuditReason()).isEqualTo("STREAM_OUTPUT_TRUNCATED");
        assertThat(events.get(3).data().getFallbackReason()).isEqualTo("OUTPUT_TRUNCATED");
        verify(qualityGuard, never()).validateText(any(), eq("chat"));
    }

    @Test
    void structuredQaStreamShouldNormalizeOnlyValidatedJson() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1").sessionId("s1").traceId("t1").build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("chat-memory");
        when(intentRouteCoordinator.route(context, "店铺1服务怎么样", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.QA).source(IntentRouteSource.RULE).confidence(0.9).shopId(1L).build());
        when(qaWorkflow.prepareStreamPlan(eq(context), any(QAWorkflowRequest.class)))
                .thenReturn(StreamWorkflowPlan.builder()
                        .analysisType("ask").memoryId("qa-memory").prompt("prompt")
                        .promptVersion("shop-qa-v3").confidence(0.8).cacheHit(false)
                        .structuredOutput(true).expectedShopId(1L).expectedQuestion("服务怎么样")
                        .build());
        when(modelGateway.streamAnswer("qa-memory", "prompt"))
                .thenReturn(reactor.core.publisher.Flux.just("{\"shopId\":1,\"answer\":\"稳定\",",
                        "\"evidenceIds\":[],\"insufficientEvidence\":false}"));
        when(qualityGuard.validateQA(any(ShopQAResult.class), eq(1L), anyList(), eq("ask")))
                .thenReturn(QualityCheck.builder().decision(QualityDecision.PASS).build());

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context,
                        ChatWorkflowRequest.builder().message("店铺1服务怎么样").build())
                .collectList().block();

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("metadata", "delta", "audit", "done");
        assertThat(events.get(1).data().getText()).contains("\"shopId\":1").contains("\"answer\":\"稳定\"");
        assertThat(events.get(2).data().getAuditStatus()).isEqualTo("PASS");
        assertThat(events.get(3).data().getDegraded()).isFalse();
    }

    @Test
    void structuredQaStreamShouldMarkTruncatedJsonInsteadOfEmittingIt() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1").sessionId("s1").traceId("t1").build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("chat-memory");
        when(intentRouteCoordinator.route(context, "店铺1服务怎么样", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.QA).source(IntentRouteSource.RULE).confidence(0.9).shopId(1L).build());
        when(qaWorkflow.prepareStreamPlan(eq(context), any(QAWorkflowRequest.class)))
                .thenReturn(StreamWorkflowPlan.builder()
                        .analysisType("ask").memoryId("qa-memory").prompt("prompt")
                        .structuredOutput(true).expectedShopId(1L).expectedQuestion("服务怎么样")
                        .build());
        when(modelGateway.streamAnswer("qa-memory", "prompt"))
                .thenReturn(reactor.core.publisher.Flux.just("{\"shopId\":1,\"answer\":\"未完成"));
        when(fallbackPolicy.fallbackQA(eq(1L), eq("服务怎么样"), eq("ask"),
                eq(FallbackReason.QUALITY_REJECTED)))
                .thenReturn(ShopQAResult.builder().shopId(1L).question("服务怎么样")
                        .answer("无法可靠回答").evidenceIds(List.of()).insufficientEvidence(true).build());

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context,
                        ChatWorkflowRequest.builder().message("店铺1服务怎么样").build())
                .collectList().block();

        assertThat(events.get(1).data().getText()).contains("无法可靠回答").doesNotContain("未完成");
        assertThat(events.get(2).data().getAuditReason()).isEqualTo("STRUCTURED_OUTPUT_INCOMPLETE");
        assertThat(events.get(3).data().getFallbackReason()).isEqualTo("STRUCTURED_OUTPUT_INCOMPLETE");
        verify(qualityGuard, never()).validateQA(any(ShopQAResult.class), anyList(), eq("ask"));
    }

    @Test
    void structuredQaStreamShouldKeepTypedContractWhenModelIsUnavailable() {
        ShopAIRequestContext context = ShopAIRequestContext.builder()
                .userId("u1").sessionId("s1").traceId("t1").build();
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("chat-memory");
        when(intentRouteCoordinator.route(context, "shop 1 service?", null)).thenReturn(IntentRoutingResult.builder()
                .intent(ShopAIIntent.QA).source(IntentRouteSource.RULE).confidence(0.9).shopId(1L).build());
        when(qaWorkflow.prepareStreamPlan(eq(context), any(QAWorkflowRequest.class)))
                .thenReturn(StreamWorkflowPlan.builder()
                        .analysisType("ask").memoryId("qa-memory").prompt("prompt")
                        .structuredOutput(true).expectedShopId(1L).expectedQuestion("service?")
                        .build());
        when(modelGateway.streamAnswer("qa-memory", "prompt"))
                .thenReturn(reactor.core.publisher.Flux.error(new RuntimeException("model unavailable")));
        when(fallbackPolicy.fallbackQA(1L, "service?", "ask", FallbackReason.MODEL_UNAVAILABLE))
                .thenReturn(ShopQAResult.builder().shopId(1L).question("service?")
                        .answer("service unavailable").evidenceIds(List.of()).insufficientEvidence(true).build());

        List<ServerSentEvent<ShopAIStreamEvent>> events = workflow.stream(context,
                        ChatWorkflowRequest.builder().message("shop 1 service?").build())
                .collectList().block();

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("metadata", "delta", "done");
        assertThat(events.get(1).data().getText())
                .contains("\"shopId\":1", "\"answer\":\"service unavailable\"");
        assertThat(events.get(1).data().getFallbackReason()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(events.get(2).data().getFallbackReason()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(events.get(2).data().getDegraded()).isTrue();
        verify(fallbackPolicy, never()).fallbackText(any(), any(), any());
    }
}
