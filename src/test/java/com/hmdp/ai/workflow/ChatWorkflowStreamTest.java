package com.hmdp.ai.workflow;

import com.hmdp.ai.intent.IntentRouteCoordinator;
import com.hmdp.ai.intent.IntentRouteSource;
import com.hmdp.ai.intent.IntentRoutingResult;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.ai.guard.QualityCheck;
import com.hmdp.ai.guard.QualityDecision;
import com.hmdp.ai.guard.QualityGuard;
import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.model.ModelGateway;
import com.hmdp.ai.orchestration.ShopAIRequestContext;
import com.hmdp.ai.prompt.PromptTemplateRegistry;
import com.hmdp.ai.workflow.request.ChatWorkflowRequest;
import com.hmdp.dto.ai.ShopAIResponse;
import com.hmdp.dto.ai.ShopAIStreamEvent;
import com.hmdp.entity.ShopSummaryResult;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWorkflowStreamTest {

    @Mock
    private IntentRouteCoordinator intentRouteCoordinator;

    @Mock
    private MemoryService memoryService;

    @Mock
    private SummaryWorkflow summaryWorkflow;

    @Mock
    private ModelGateway modelGateway;

    @Mock
    private QualityGuard qualityGuard;

    private ChatWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new ChatWorkflow();
        ReflectionTestUtils.setField(workflow, "intentRouteCoordinator", intentRouteCoordinator);
        ReflectionTestUtils.setField(workflow, "memoryService", memoryService);
        ReflectionTestUtils.setField(workflow, "summaryWorkflow", summaryWorkflow);
        ReflectionTestUtils.setField(workflow, "modelGateway", modelGateway);
        ReflectionTestUtils.setField(workflow, "qualityGuard", qualityGuard);
        ReflectionTestUtils.setField(workflow, "promptTemplateRegistry", new PromptTemplateRegistry());
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
}
