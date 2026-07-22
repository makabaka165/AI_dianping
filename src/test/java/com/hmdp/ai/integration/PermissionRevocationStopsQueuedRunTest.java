package com.hmdp.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.agent.event.RunEventPublisher;
import com.hmdp.ai.application.security.AiAuthorizationService;
import com.hmdp.ai.domain.observability.AiTraceContext;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.runtime.agent.AgentContextAssembler;
import com.hmdp.ai.runtime.agent.AgentExecutionEngine;
import com.hmdp.ai.runtime.agent.AgentOutputValidator;
import com.hmdp.ai.runtime.agent.AgentRuntimeProperties;
import com.hmdp.ai.runtime.agent.DefaultAgentRuntime;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PermissionRevocationStopsQueuedRunTest {
    @Test
    void revokedCurrentPermissionFailsQueuedRunBeforeDefinitionNodeOrWorkflowExecution() throws Exception {
        ObjectMapper mapper = RuntimeIntegrationTestSupport.MAPPER;
        RuntimeIntegrationTestSupport.RecordingRunRepository runs =
                new RuntimeIntegrationTestSupport.RecordingRunRepository();
        AgentRunRecord queued = queuedRun();
        runs.create(queued);
        RuntimeIntegrationTestSupport.RecordingNodeRunRepository nodeRuns =
                new RuntimeIntegrationTestSupport.RecordingNodeRunRepository();
        AgentDefinitionLoader definitions = mock(AgentDefinitionLoader.class);
        AgentContextAssembler contexts = mock(AgentContextAssembler.class);
        AgentExecutionEngine engine = mock(AgentExecutionEngine.class);
        AgentOutputValidator outputs = mock(AgentOutputValidator.class);
        AiAuthorizationService authorization = mock(AiAuthorizationService.class);
        when(authorization.authorize("revoked-user", "tenant", "workspace"))
                .thenReturn(new AuthorizationContext(Collections.emptySet()));
        ThreadPoolTaskExecutor executor = RuntimeIntegrationTestSupport.executor("permission-integration-");
        try {
            RunEventPublisher events = new RunEventPublisher(runs, mapper,
                    mock(com.hmdp.ai.application.agent.event.SseRunEventHub.class));
            DefaultAgentRuntime runtime = new DefaultAgentRuntime(runs, nodeRuns, definitions, contexts,
                    engine, outputs, events, mapper, executor, new AgentRuntimeProperties(),
                    Collections.emptyList(), mock(AiMetricsService.class), mock(AiTraceContext.class),
                    authorization, new RunCancellationRegistry());

            runtime.enqueue("tenant", "workspace", queued.getId());

            assertThat(runs.awaitTerminal(5, TimeUnit.SECONDS)).isTrue();
            waitForFailureEvent(runs, queued.getId());
            AgentRunRecord stored = runs.find("tenant", "workspace", queued.getId())
                    .orElseThrow(AssertionError::new);
            assertThat(stored.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(stored.getErrorCode()).isEqualTo("PERMISSION_REVOKED");
            assertThat(runs.findEvents("tenant", "workspace", queued.getId(), 0, 10))
                    .extracting(value -> value.getType())
                    .containsExactly("run.failed");
            assertThat(runs.findEvents("tenant", "workspace", queued.getId(), 0, 10).get(0)
                    .getPayloadJson()).contains("PERMISSION_REVOKED");
            assertThat(nodeRuns.starts).isEmpty();
            verifyNoInteractions(definitions, contexts, engine, outputs);
        } finally {
            executor.shutdown();
        }
    }

    private AgentRunRecord queuedRun() {
        Instant now = Instant.now();
        return new AgentRunRecord("revoked-run", "tenant", "workspace", "revoked-user", "session",
                "conversation", "agent-id", 1, RunStatus.QUEUED, "BLOCKING", "{\"text\":\"hello\"}",
                null, "{}", "{}", "{\"maxRunDurationSeconds\":120}",
                "{\"permissions\":[\"AGENT_RUN\"]}", "trace", null, 1, null, null,
                now, null, null, now.plusSeconds(120), now);
    }

    private void waitForFailureEvent(RuntimeIntegrationTestSupport.RecordingRunRepository runs,
                                     String runId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (runs.findEvents("tenant", "workspace", runId, 0, 10).isEmpty()
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
