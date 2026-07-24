package com.hmdp.ai.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.agent.AgentRuntime;
import com.hmdp.ai.application.security.AiAuthorizationService;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.NodeRunRepository;
import com.hmdp.ai.domain.run.NodeRunClaim;
import com.hmdp.ai.domain.run.NodeRunStatus;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.domain.observability.AiTraceContext;
import com.hmdp.ai.application.agent.event.RunEventPublisher;
import com.hmdp.ai.domain.run.RunLifecycleEventPayload;
import com.hmdp.ai.domain.run.RunCompletionObserver;
import com.hmdp.ai.domain.run.WorkflowWaitEventPayload;
import com.hmdp.ai.runtime.workflow.WorkflowPausedException;
import com.hmdp.ai.runtime.cancellation.CancellationToken;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import com.hmdp.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;

@Component
@Slf4j
public class DefaultAgentRuntime implements AgentRuntime {
    private static final String RUNTIME_NODE = "agent-workflow-runtime";
    private final RunRepository runs;
    private final NodeRunRepository nodeRuns;
    private final AgentDefinitionLoader definitionLoader;
    private final AgentContextAssembler contextAssembler;
    private final AgentExecutionEngine executionEngine;
    private final AgentOutputValidator outputValidator;
    private final RunEventPublisher events;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor executor;
    private final AgentRuntimeProperties properties;
    private final List<RunCompletionObserver> completionObservers;
    private final AiMetricsService metrics;
    private final AiTraceContext traceContext;
    private final AiAuthorizationService authorization;
    private final RunCancellationRegistry cancellations;

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultAgentRuntime(RunRepository runs, NodeRunRepository nodeRuns,
                               AgentDefinitionLoader definitionLoader, AgentContextAssembler contextAssembler,
                               AgentExecutionEngine executionEngine, AgentOutputValidator outputValidator,
                               RunEventPublisher events, ObjectMapper objectMapper,
                               @Qualifier("agentRunExecutor") ThreadPoolTaskExecutor executor,
                               AgentRuntimeProperties properties, List<RunCompletionObserver> completionObservers,
                               AiMetricsService metrics, AiTraceContext traceContext,
                               AiAuthorizationService authorization, RunCancellationRegistry cancellations) {
        this.runs = runs;
        this.nodeRuns = nodeRuns;
        this.definitionLoader = definitionLoader;
        this.contextAssembler = contextAssembler;
        this.executionEngine = executionEngine;
        this.outputValidator = outputValidator;
        this.events = events;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.properties = properties;
        this.completionObservers = completionObservers;
        this.metrics = metrics;
        this.traceContext = traceContext;
        this.authorization = authorization;
        this.cancellations = cancellations;
    }

    public DefaultAgentRuntime(RunRepository runs, NodeRunRepository nodeRuns,
                               AgentDefinitionLoader definitionLoader, AgentContextAssembler contextAssembler,
                               AgentExecutionEngine executionEngine, AgentOutputValidator outputValidator,
                               RunEventPublisher events, ObjectMapper objectMapper,
                               ThreadPoolTaskExecutor executor, AgentRuntimeProperties properties,
                               List<RunCompletionObserver> completionObservers, AiMetricsService metrics,
                               AiTraceContext traceContext) {
        this(runs, nodeRuns, definitionLoader, contextAssembler, executionEngine, outputValidator, events,
                objectMapper, executor, properties, completionObservers, metrics, traceContext, null, null);
    }

    @Override
    public void enqueue(String tenantId, String workspaceId, String runId) {
        try {
            executor.execute(() -> execute(tenantId, workspaceId, runId));
        } catch (TaskRejectedException e) {
            runs.fail(tenantId, workspaceId, runId, "AGENT_RUNTIME_QUEUE_FULL",
                    "agent runtime queue is full", RunStatus.FAILED);
            events.publish(tenantId, workspaceId, runId, "run.failed",
                    new RunLifecycleEventPayload(runId, RunStatus.FAILED, null,
                            "AGENT_RUNTIME_QUEUE_FULL", "runtime queue is full"), true);
        }
    }

    @Override
    public void recover() {
        runs.requeueInterruptedRuns();
        for (AgentRunRecord run : runs.findRecoverable(properties.getRecoveryBatchSize())) {
            enqueue(run.getTenantId(), run.getWorkspaceId(), run.getId());
        }
    }

    private void execute(String tenantId, String workspaceId, String runId) {
        String nodeRunId = null;
        CancellationToken cancellation = cancellations == null ? null : cancellations.begin(runId);
        try {
            AgentRunRecord run = runs.find(tenantId, workspaceId, runId).orElse(null);
            if (run == null || run.getStatus().isTerminal()) return;
            if (authorization != null) revalidate(run);
            if (cancellation != null) cancellation.throwIfCancelled();
            traceContext.bind(run,null);
            if (!run.getDeadlineAt().isAfter(Instant.now())) {
                timeout(run, null);
                return;
            }
            if (!runs.claimQueued(tenantId, workspaceId, runId)) return;
            metrics.recordAgentRunStarted(run.getAgentId());
            events.publish(tenantId, workspaceId, runId, "run.started",
                    new RunLifecycleEventPayload(runId, RunStatus.RUNNING, null, null, "run started"), false);
            AgentInputRequest input = objectMapper.readValue(run.getInputJson(), AgentInputRequest.class);
            PublishedAgentDefinition definition = definitionLoader.load(tenantId, workspaceId,
                    run.getAgentId(), run.getAgentVersion());
            ExecutionContext context = contextAssembler.assemble(run, definition, input);
            String inputSummary = objectMapper.writeValueAsString(input);
            NodeRunClaim nodeClaim = nodeRuns.start(context, RUNTIME_NODE, "AGENT_WORKFLOW",
                    inputSummary, runId + ':' + RUNTIME_NODE + ":1");
            nodeRunId = nodeClaim.getNodeRunId();
            events.publish(tenantId, workspaceId, runId, "node.started",
                    new RunLifecycleEventPayload(runId, RunStatus.RUNNING, RUNTIME_NODE,
                            null, "agent workflow runtime started"), false);
            if (!nodeClaim.isClaimed()) {
                AgentRunRecord current = runs.find(tenantId, workspaceId, runId).orElse(run);
                if (current.getStatus() == RunStatus.CANCELLED) return;
                events.publish(tenantId, workspaceId, runId, "node.completed",
                        new RunLifecycleEventPayload(runId, RunStatus.RUNNING, RUNTIME_NODE,
                                null, "idempotent node output restored"), false);
                notifyCompletion(current, nodeClaim.getOutputJson());
                runs.complete(tenantId, workspaceId, runId, nodeClaim.getOutputJson());
                metrics.recordAgentRunCompleted(run.getAgentId(),duration(run));
                events.publish(tenantId, workspaceId, runId, "run.completed",
                        new RunLifecycleEventPayload(runId, RunStatus.COMPLETED, null, null,
                                "run completed from persisted node output"), true);
                return;
            }
            AgentRunOutput output = executionEngine.execute(definition, context, input);
            if (cancellation != null) cancellation.throwIfCancelled();
            outputValidator.validate(definition.getVersion().getOutputSchema(), output);
            AgentRunRecord current = runs.find(tenantId, workspaceId, runId).orElse(run);
            if (current.getStatus() == RunStatus.CANCELLED) {
                nodeRuns.fail(tenantId, workspaceId, nodeRunId, NodeRunStatus.CANCELLED,
                        "RUN_CANCELLED", "run was cancelled", false);
                return;
            }
            String outputJson = objectMapper.writeValueAsString(output);
            nodeRuns.complete(tenantId, workspaceId, nodeRunId, outputJson,
                    objectMapper.writeValueAsString(output.getUsage()));
            events.publish(tenantId, workspaceId, runId, "node.completed",
                    new RunLifecycleEventPayload(runId, RunStatus.RUNNING, RUNTIME_NODE,
                            null, "agent workflow runtime completed"), false);
            notifyCompletion(run, outputJson);
            runs.complete(tenantId, workspaceId, runId, outputJson);
            metrics.recordAgentRunCompleted(run.getAgentId(),duration(run));
            events.publish(tenantId, workspaceId, runId, "run.completed",
                    new RunLifecycleEventPayload(runId, RunStatus.COMPLETED, null, null,
                            "run completed"), true);
        } catch (WorkflowPausedException paused) {
            if (nodeRunId != null) {
                nodeRuns.waitForResume(tenantId, workspaceId, nodeRunId,
                        "{\"waitingNode\":\"" + paused.getNodeCode() + "\"}");
            }
            String eventType = paused.getRunStatus() == RunStatus.WAITING_FOR_APPROVAL
                    ? "approval.required" : "feedback.required";
            events.publish(tenantId, workspaceId, runId, eventType,
                    new WorkflowWaitEventPayload(runId, paused.getRunStatus(), paused.getNodeCode(),
                            paused.getResumeToken(), paused.getExpiresAt(), paused.getQuestions()), true);
        } catch (CancellationException cancelled) {
            markCancelled(tenantId, workspaceId, runId, nodeRunId);
        } catch (Exception e) {
            fail(tenantId, workspaceId, runId, nodeRunId, e);
        } finally {
            traceContext.clear();
            if (cancellations != null) cancellations.end(runId);
        }
    }

    private void markCancelled(String tenantId, String workspaceId, String runId, String nodeRunId) {
        if (nodeRunId != null) {
            nodeRuns.fail(tenantId, workspaceId, nodeRunId, NodeRunStatus.CANCELLED,
                    "RUN_CANCELLED", "run was cancelled", false);
        }
        AgentRunRecord current = runs.find(tenantId, workspaceId, runId).orElse(null);
        if (current != null && current.getStatus() != RunStatus.CANCELLED) {
            runs.cancel(tenantId, workspaceId, runId, current.getUserId());
        }
        if (current == null || current.getStatus() != RunStatus.CANCELLED) {
            events.publish(tenantId, workspaceId, runId, "run.cancelled",
                    new RunLifecycleEventPayload(runId, RunStatus.CANCELLED, null,
                            "RUN_CANCELLED", "run cancelled"), true);
        }
    }

    private void revalidate(AgentRunRecord run) {
        if (!authorization.authorize(run.getUserId(), run.getTenantId(), run.getWorkspaceId())
                .has(AiPermission.AGENT_RUN)) {
            throw new PermissionRevokedException();
        }
    }

    private void timeout(AgentRunRecord run, String nodeRunId) {
        if (nodeRunId != null) {
            nodeRuns.fail(run.getTenantId(), run.getWorkspaceId(), nodeRunId, NodeRunStatus.TIMED_OUT,
                    "RUN_DEADLINE_EXCEEDED", "run deadline exceeded", false);
        }
        runs.fail(run.getTenantId(), run.getWorkspaceId(), run.getId(), "RUN_DEADLINE_EXCEEDED",
                "run deadline exceeded", RunStatus.TIMED_OUT);
        metrics.recordAgentRunFailed(run.getAgentId(),"RUN_DEADLINE_EXCEEDED",duration(run));
        events.publish(run.getTenantId(), run.getWorkspaceId(), run.getId(), "run.failed",
                new RunLifecycleEventPayload(run.getId(), RunStatus.TIMED_OUT, null,
                        "RUN_DEADLINE_EXCEEDED", "run deadline exceeded"), true);
    }

    private void fail(String tenantId, String workspaceId, String runId, String nodeRunId, Exception error) {
        AgentRunRecord current = runs.find(tenantId, workspaceId, runId).orElse(null);
        if (current != null && current.getStatus() == RunStatus.CANCELLED) return;
        String code = errorCode(error);
        String message = AiLogSanitizer.safe(error.getMessage(), 500);
        if (message == null || message.trim().isEmpty()) message = "agent execution failed";
        if (nodeRunId != null) {
            nodeRuns.fail(tenantId, workspaceId, nodeRunId, NodeRunStatus.FAILED, code, message, false);
        }
        runs.fail(tenantId, workspaceId, runId, code, message, RunStatus.FAILED);
        if(current!=null)metrics.recordAgentRunFailed(current.getAgentId(),code,duration(current));
        try {
            events.publish(tenantId, workspaceId, runId, "run.failed",
                    new RunLifecycleEventPayload(runId, RunStatus.FAILED, null, code, message), true);
        } catch (Exception eventError) {
            log.error("failed to persist terminal run event, runId={}", runId, eventError);
        }
        log.error("agent run failed, runId={}, errorCode={}", runId, code, error);
    }

    private String errorCode(Exception error) {
        if (error instanceof PermissionRevokedException) {
            return "PERMISSION_REVOKED";
        }
        if (error instanceof AiPlatformException) {
            return ((AiPlatformException) error).getErrorCode().name();
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "AGENT_EXECUTION_TIMEOUT";
        }
        return ErrorCode.AI_EXECUTION_FAILED.name();
    }

    private static final class PermissionRevokedException extends RuntimeException {
        private PermissionRevokedException() {
            super("agent run permission was revoked");
        }
    }

    private void notifyCompletion(AgentRunRecord run, String outputJson) {
        if (completionObservers == null) {
            return;
        }
        for (RunCompletionObserver observer : completionObservers) {
            if (observer != null) {
                observer.onCompleted(run, outputJson);
            }
        }
    }
    private long duration(AgentRunRecord run){Instant start=run.getStartedAt()==null?run.getQueuedAt():run.getStartedAt();return start==null?0:Math.max(0,java.time.Duration.between(start,Instant.now()).toMillis());}
}
