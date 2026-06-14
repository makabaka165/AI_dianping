package com.hmdp.ai.application;

import com.hmdp.ai.memory.MemoryService;
import com.hmdp.ai.orchestration.ShopAIOrchestrator;
import com.hmdp.dto.ai.ShopAIRequestContext;
import com.hmdp.ai.quota.AiUserQuotaService;
import com.hmdp.ai.workflow.request.ChatWorkflowRequest;
import com.hmdp.ai.memory.ChatMemoryKeyManager;
import com.hmdp.dto.ai.ShopAIStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopAIApplicationServiceTest {

    @Mock
    private ShopAIOrchestrator orchestrator;

    @Mock
    private MemoryService memoryService;

    @Mock
    private AiUserQuotaService aiUserQuotaService;

    private ShopAIApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ShopAIApplicationService();
        ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
        ReflectionTestUtils.setField(service, "memoryService", memoryService);
        ReflectionTestUtils.setField(service, "aiUserQuotaService", aiUserQuotaService);
        ReflectionTestUtils.setField(service, "keyManager", new ChatMemoryKeyManager());
    }

    @Test
    void chatStreamShouldPassExplicitShopIdToWorkflowRequest() {
        when(memoryService.aiChatKey("u1", "s1")).thenReturn("m1");
        when(orchestrator.chatStream(any(ShopAIRequestContext.class), any(ChatWorkflowRequest.class)))
                .thenReturn(Flux.just(ServerSentEvent.<ShopAIStreamEvent>builder().event("done").build()));

        service.chatStream("u1", "s1", "service?", 12L, "/stream")
                .collectList()
                .block();

        ArgumentCaptor<ChatWorkflowRequest> requestCaptor = ArgumentCaptor.forClass(ChatWorkflowRequest.class);
        verify(orchestrator).chatStream(any(ShopAIRequestContext.class), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getShopId()).isEqualTo(12L);
        verify(aiUserQuotaService).checkAndConsume("u1", "chatStream");
    }
}
