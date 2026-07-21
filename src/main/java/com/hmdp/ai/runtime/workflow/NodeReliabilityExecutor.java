package com.hmdp.ai.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.run.NodeRunStatus;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.runtime.cancellation.NodeCancellationToken;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import com.hmdp.ai.runtime.node.NodeExecutionContext;
import com.hmdp.ai.runtime.node.NodeExecutionResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class NodeReliabilityExecutor {
    private final ThreadPoolTaskExecutor executor;
    private final RunCancellationRegistry cancellations;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public NodeReliabilityExecutor(@Qualifier("workflowNodeExecutor") ThreadPoolTaskExecutor executor,
                                   RunCancellationRegistry cancellations, ObjectMapper mapper,
                                   MeterRegistry metrics) {
        this.executor = executor;
        this.cancellations = cancellations;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public NodeReliabilityExecutor(ThreadPoolTaskExecutor executor, RunCancellationRegistry cancellations,
                                   ObjectMapper mapper) {
        this(executor, cancellations, mapper, Metrics.globalRegistry);
    }

    public NodeExecutionResult execute(WorkflowNodeDefinition node, NodeExecutionContext context,
                                       Callable<NodeExecutionResult> invocation) {
        long started = System.nanoTime();
        try {
            return executeWithPolicy(NodeExecutionPolicy.from(node, mapper), node, context, invocation);
        } finally {
            metrics.timer("ai.workflow.node.duration", "nodeType", node.getType().name())
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private NodeExecutionResult executeWithPolicy(NodeExecutionPolicy policy, WorkflowNodeDefinition node,
                                                  NodeExecutionContext context,
                                                  Callable<NodeExecutionResult> invocation) {
        NodeCancellationToken token = context.getCancellationToken();
        NodeExecutionResult last = NodeExecutionResult.failure("NODE_MAX_ATTEMPTS_EXCEEDED", true);
        for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
            try {
                token.throwIfCancellationRequested();
            } catch (NodeTimeoutException e) {
                return NodeExecutionResult.failure(e.getMessage(), false);
            }
            long timeoutMs = policy.effectiveTimeoutMs(context.getExecutionContext());
            if (timeoutMs <= 0) {
                return NodeExecutionResult.failure("NODE_DEADLINE_EXCEEDED", false);
            }

            Future<NodeExecutionResult> future = executor.submit(() -> {
                token.throwIfCancellationRequested();
                NodeExecutionResult result = invocation.call();
                token.throwIfCancellationRequested();
                return result;
            });
            track(context.getExecutionContext().getRunId(), future);
            NodeRetryDecision decision;
            try {
                NodeExecutionResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (result.getStatus() != NodeRunStatus.FAILED) {
                    return result;
                }
                last = result;
                decision = policy.decide(result.getErrorCode(), result.isRetryable(), attempt);
            } catch (TimeoutException e) {
                future.cancel(true);
                last = NodeExecutionResult.failure("NODE_TIMEOUT", true);
                decision = policy.decide("NODE_TIMEOUT", true, attempt);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new CancellationException("RUN_CANCELLED");
            } catch (CancellationException e) {
                future.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                if (isCancellation(e.getCause())) {
                    future.cancel(true);
                    throw new CancellationException("RUN_CANCELLED");
                }
                String errorCode = errorCode(e.getCause());
                last = NodeExecutionResult.failure(errorCode, true);
                decision = policy.decide(errorCode, true, attempt);
            } finally {
                untrack(context.getExecutionContext().getRunId(), future);
            }

            if (!decision.shouldRetry()) {
                return last;
            }
            metrics.counter("ai.workflow.node.retry", "nodeType", node.getType().name()).increment();
            try {
                token.awaitBackoff(decision.getBackoffMs());
            } catch (NodeTimeoutException e) {
                return NodeExecutionResult.failure("NODE_DEADLINE_EXCEEDED", false);
            }
        }
        return last;
    }

    private boolean isCancellation(Throwable cause) {
        return cause instanceof CancellationException || cause instanceof InterruptedException;
    }

    private String errorCode(Throwable cause) {
        String message = cause == null ? null : cause.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]+")
                ? message : "NODE_EXECUTION_FAILED";
    }

    private void track(String runId, Future<?> future) {
        if (cancellations != null) {
            cancellations.track(runId, future);
        }
    }

    private void untrack(String runId, Future<?> future) {
        if (cancellations != null) {
            cancellations.untrack(runId, future);
        }
    }
}
