package com.hmdp.ai.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.NodeRunStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.runtime.cancellation.CancellationToken;
import com.hmdp.ai.runtime.cancellation.NodeCancellationToken;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import com.hmdp.ai.runtime.node.NodeExecutionContext;
import com.hmdp.ai.runtime.node.NodeExecutionResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeReliabilityExecutorTest {
    private ThreadPoolTaskExecutor executor;
    private RunCancellationRegistry cancellations;
    private SimpleMeterRegistry metrics;
    private NodeReliabilityExecutor reliability;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.initialize();
        cancellations = new RunCancellationRegistry();
        metrics = new SimpleMeterRegistry();
        reliability = new NodeReliabilityExecutor(executor, cancellations, new ObjectMapper(), metrics);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        metrics.close();
    }

    @Test
    void retriesOnlyRetryableErrorsAndStopsAtConfiguredAttempt() {
        WorkflowNodeDefinition node = node(500, 3,
                "{\"retryPolicy\":{\"backoffMs\":1,\"retryableErrors\":[\"TRANSIENT\"]}}");
        AtomicInteger attempts = new AtomicInteger();

        NodeExecutionResult result = reliability.execute(node, context(node, token()), () -> {
            if (attempts.incrementAndGet() < 3) {
                return NodeExecutionResult.failure("TRANSIENT", true);
            }
            return NodeExecutionResult.success(JsonNodeFactory.instance.objectNode().put("ok", true),
                    null, null);
        });

        assertEquals(NodeRunStatus.SUCCEEDED, result.getStatus());
        assertEquals(3, attempts.get());
        assertEquals(2.0, metrics.counter("ai.workflow.node.retry", "nodeType", "DATA_TRANSFORM").count());
    }

    @Test
    void doesNotRetryErrorOutsidePolicyAllowlist() {
        WorkflowNodeDefinition node = node(500, 3,
                "{\"retryPolicy\":{\"retryableErrors\":[\"TRANSIENT\"]}}");
        AtomicInteger attempts = new AtomicInteger();

        NodeExecutionResult result = reliability.execute(node, context(node, token()), () -> {
            attempts.incrementAndGet();
            return NodeExecutionResult.failure("PERMANENT", true);
        });

        assertEquals(NodeRunStatus.FAILED, result.getStatus());
        assertEquals("PERMANENT", result.getErrorCode());
        assertEquals(1, attempts.get());
    }

    @Test
    void timesOutAndCancelsEveryAttempt() {
        WorkflowNodeDefinition node = node(20, 2,
                "{\"retryPolicy\":{\"retryableErrors\":[\"NODE_TIMEOUT\"]}}");
        AtomicInteger attempts = new AtomicInteger();

        NodeExecutionResult result = reliability.execute(node, context(node, token()), () -> {
            attempts.incrementAndGet();
            Thread.sleep(500);
            return NodeExecutionResult.success(JsonNodeFactory.instance.objectNode(), null, null);
        });

        assertEquals(NodeRunStatus.FAILED, result.getStatus());
        assertEquals("NODE_TIMEOUT", result.getErrorCode());
        assertEquals(2, attempts.get());
    }

    @Test
    void cancelledRunNeverStartsNodeInvocation() {
        WorkflowNodeDefinition node = node(500, 2, "{}");
        CancellationToken runToken = cancellations.begin("run");
        cancellations.cancel("run");
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(CancellationException.class, () -> reliability.execute(node,
                context(node, new NodeCancellationToken(runToken, Instant.now().plusSeconds(30))), () -> {
                    attempts.incrementAndGet();
                    return NodeExecutionResult.success(JsonNodeFactory.instance.objectNode(), null, null);
                }));
        assertEquals(0, attempts.get());
    }

    private WorkflowNodeDefinition node(int timeoutMs, int maxAttempts, String configuration) {
        return new WorkflowNodeDefinition("node", "node", WorkflowNodeType.DATA_TRANSFORM, "Node",
                configuration, "{}", "{}", timeoutMs, maxAttempts);
    }

    private NodeExecutionContext context(WorkflowNodeDefinition node, NodeCancellationToken token) {
        ExecutionContext execution = new ExecutionContext("tenant", "workspace", "user", "session", null,
                "run", "agent", 1, "en-US", "UTC", Collections.emptyList(), Collections.emptyList(),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), ExecutionBudget.defaults(),
                Instant.now().plusSeconds(30), Collections.emptyMap(), "trace");
        return new NodeExecutionContext(execution, null, null, node, Collections.emptyMap(),
                Collections.emptyList(), "node-run", token);
    }

    private NodeCancellationToken token() {
        return new NodeCancellationToken(cancellations.begin("run"), Instant.now().plusSeconds(30));
    }
}
