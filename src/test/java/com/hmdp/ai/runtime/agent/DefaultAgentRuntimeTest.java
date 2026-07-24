package com.hmdp.ai.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.NodeRunClaim;
import com.hmdp.ai.domain.run.NodeRunRepository;
import com.hmdp.ai.domain.run.RunCompletionObserver;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.application.agent.event.RunEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAgentRuntimeTest {
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void claimsPersistsNodeAndCompletesRunThroughBoundedExecutor() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RunRepository runs = mock(RunRepository.class);
        NodeRunRepository nodes = mock(NodeRunRepository.class);
        AgentDefinitionLoader loader = mock(AgentDefinitionLoader.class);
        AgentContextAssembler contextAssembler = mock(AgentContextAssembler.class);
        AgentExecutionEngine engine = mock(AgentExecutionEngine.class);
        AgentOutputValidator outputValidator = mock(AgentOutputValidator.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCompletionObserver completionObserver = mock(RunCompletionObserver.class);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        AgentInputRequest input = new AgentInputRequest();
        input.setText("hello");
        AgentRunRecord run = run(mapper.writeValueAsString(input));
        PublishedAgentDefinition definition = definition();
        ExecutionContext executionContext = new ExecutionContext("tenant", "workspace", "user", "session",
                "conversation", "run", "agent", 1, "zh-CN", "Asia/Shanghai",
                Collections.emptyList(), Collections.emptyList(),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), ExecutionBudget.defaults(),
                Instant.now().plusSeconds(120), Collections.emptyMap(), "trace");
        AgentRunOutput output = new AgentRunOutput("answer", Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), UsageSummary.empty(10), Collections.emptyList(), RunStatus.COMPLETED);
        CountDownLatch completed = new CountDownLatch(1);

        when(runs.find("tenant", "workspace", "run")).thenReturn(Optional.of(run));
        when(runs.claimQueued("tenant", "workspace", "run")).thenReturn(true);
        when(loader.load("tenant", "workspace", "agent", 1)).thenReturn(definition);
        when(contextAssembler.assemble(any(AgentRunRecord.class), any(PublishedAgentDefinition.class),
                any(AgentInputRequest.class))).thenReturn(executionContext);
        when(nodes.start(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new NodeRunClaim("node-run", true, null));
        when(engine.execute(any(PublishedAgentDefinition.class), any(ExecutionContext.class),
                any(AgentInputRequest.class))).thenReturn(output);
        doAnswer(invocation -> { completed.countDown(); return null; })
                .when(runs).complete(anyString(), anyString(), anyString(), anyString());

        DefaultAgentRuntime runtime = new DefaultAgentRuntime(runs, nodes, loader, contextAssembler, engine,
                outputValidator, events, mapper, executor, properties,
                java.util.Collections.singletonList(completionObserver),
                mock(com.hmdp.ai.infra.AiMetricsService.class),
                mock(com.hmdp.ai.domain.observability.AiTraceContext.class));
        runtime.enqueue("tenant", "workspace", "run");

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        verify(nodes).complete(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(outputValidator).validate(anyString(), any(AgentRunOutput.class));
        verify(runs).complete(anyString(), anyString(), anyString(), anyString());
        InOrder completionOrder = inOrder(completionObserver, runs);
        completionOrder.verify(completionObserver).onCompleted(any(AgentRunRecord.class), anyString());
        completionOrder.verify(runs).complete(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void completionObserverFailureMustFailRunBeforeCompletionIsPublished() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RunRepository runs = mock(RunRepository.class);
        NodeRunRepository nodes = mock(NodeRunRepository.class);
        AgentDefinitionLoader loader = mock(AgentDefinitionLoader.class);
        AgentContextAssembler contextAssembler = mock(AgentContextAssembler.class);
        AgentExecutionEngine engine = mock(AgentExecutionEngine.class);
        AgentOutputValidator outputValidator = mock(AgentOutputValidator.class);
        RunEventPublisher events = mock(RunEventPublisher.class);
        RunCompletionObserver completionObserver = mock(RunCompletionObserver.class);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        AgentRunRecord run = run(mapper.writeValueAsString(new AgentInputRequest()));
        ExecutionContext executionContext = new ExecutionContext("tenant", "workspace", "user", "session",
                "conversation", "run", "agent", 1, "zh-CN", "Asia/Shanghai",
                Collections.emptyList(), Collections.emptyList(),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), ExecutionBudget.defaults(),
                Instant.now().plusSeconds(120), Collections.emptyMap(), "trace");
        AgentRunOutput output = new AgentRunOutput("answer", Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), UsageSummary.empty(10), Collections.emptyList(), RunStatus.COMPLETED);
        CountDownLatch failed = new CountDownLatch(1);

        when(runs.find("tenant", "workspace", "run")).thenReturn(Optional.of(run));
        when(runs.claimQueued("tenant", "workspace", "run")).thenReturn(true);
        when(loader.load("tenant", "workspace", "agent", 1)).thenReturn(definition());
        when(contextAssembler.assemble(any(AgentRunRecord.class), any(PublishedAgentDefinition.class),
                any(AgentInputRequest.class))).thenReturn(executionContext);
        when(nodes.start(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new NodeRunClaim("node-run", true, null));
        when(engine.execute(any(PublishedAgentDefinition.class), any(ExecutionContext.class),
                any(AgentInputRequest.class))).thenReturn(output);
        doThrow(new IllegalStateException("memory store down"))
                .when(completionObserver).onCompleted(any(AgentRunRecord.class), anyString());
        doAnswer(invocation -> {
            failed.countDown();
            return null;
        }).when(runs).fail(anyString(), anyString(), anyString(), anyString(), anyString(), any(RunStatus.class));

        DefaultAgentRuntime runtime = new DefaultAgentRuntime(runs, nodes, loader, contextAssembler, engine,
                outputValidator, events, mapper, executor, properties,
                Collections.singletonList(completionObserver),
                mock(com.hmdp.ai.infra.AiMetricsService.class),
                mock(com.hmdp.ai.domain.observability.AiTraceContext.class));
        runtime.enqueue("tenant", "workspace", "run");

        assertTrue(failed.await(5, TimeUnit.SECONDS));
        verify(runs, never()).complete(anyString(), anyString(), anyString(), anyString());
        verify(runs).fail(anyString(), anyString(), anyString(), anyString(), anyString(), any(RunStatus.class));
    }

    private AgentRunRecord run(String inputJson) {
        Instant now = Instant.now();
        return new AgentRunRecord("run", "tenant", "workspace", "user", "session", "conversation",
                "agent", 1, RunStatus.QUEUED, "BLOCKING", inputJson, null, "{}", "{}",
                "{\"maxRunDurationSeconds\":120}", "{\"permissions\":[\"AGENT_RUN\"]}",
                "trace", null, 1, null, null, now, null, null, now.plusSeconds(120), now);
    }

    private PublishedAgentDefinition definition() {
        AgentDefinition agent = new AgentDefinition("agent", "tenant", "workspace", "shop-consultant",
                "Shop", "description", 1, "ACTIVE", null, null);
        AgentVersion version = new AgentVersion("agent-v1", "tenant", "workspace", "agent", 1,
                "Shop", "description", "model", "prompt-v1", "workflow-v1", "{}",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", "{}", "{}",
                VersionStatus.PUBLISHED, "hash", "change", null, null, null);
        ModelProfile model = new ModelProfile("model", "tenant", "workspace", "model", "Model", "provider",
                "name", "https://example.com/v1", "env:AI_CHAT_API_KEY", ModelType.CHAT,
                "{\"streaming\":true,\"toolCalling\":true,\"jsonSchema\":true,\"vision\":false,\"longContext\":true}",
                "{}", 32000, 1000, 30000, "{}", null, BigDecimal.ZERO, BigDecimal.ZERO,
                true, 1, "ACTIVE", null, null);
        PromptVersion prompt = new PromptVersion("prompt-v1", "tenant", "workspace", "prompt", 1,
                "system", "task", null, null, null, "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", "[]",
                VersionStatus.PUBLISHED, "hash", "change", null, null, null);
        return new PublishedAgentDefinition(agent, version, model, prompt, "workflow", 1, "PUBLISHED",
                Collections.emptyList(), Collections.emptyList(),
                new com.hmdp.ai.domain.run.VersionSnapshot("agent", 1, "prompt", 1, "workflow", 1,
                        "model", 1, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap()));
    }
}
