package com.hmdp.ai.runtime.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.agent.AgentRuntime;
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
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.ai.application.agent.event.RunEventPublisher;
import com.hmdp.ai.domain.run.RunLifecycleEventPayload;
import com.hmdp.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Slf4j
public class DefaultAgentRuntime implements AgentRuntime {
    private static final String COMPATIBILITY_NODE = "shop-compatibility-execution";
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

    public DefaultAgentRuntime(RunRepository runs, NodeRunRepository nodeRuns,
                               AgentDefinitionLoader definitionLoader, AgentContextAssembler contextAssembler,
                               AgentExecutionEngine executionEngine, AgentOutputValidator outputValidator,
                               RunEventPublisher events, ObjectMapper objectMapper,
                               @Qualifier("agentRunExecutor") ThreadPoolTaskExecutor executor,
                               AgentRuntimeProperties properties) {
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
        try {
            AgentRunRecord run = runs.find(tenantId, workspaceId, runId).orElse(null);
            if (run == null || run.getStatus().isTerminal()) return;
            if (!run.getDeadlineAt().isAfter(Instant.now())) {
                timeout(run, null);
                return;
            }
            if (!runs.claimQueued(tenantId, workspaceId, runId)) return;
            events.publish(tenantId, workspaceId, runId, "run.started",
                    new RunLifecycleEventPayload(runId, RunStatus.RUNNING, null, null, "run started"), false);
            AgentInputRequest input = objectMapper.readValue(run.getInputJson(), AgentInputRequest.class);
            PublishedAgentDefinition definition = definitionLoader.load(tenantId, workspaceId,
                    run.getAgentId(), run.getAgentVersion());
            ExecutionContext context = contextAssembler.assemble(run, definition, input);
            String inputSummary = objectMapper.writeValueAsString(input);
            NodeRunClaim nodeClaim = nodeRuns.start(context, COMPATIBILITY_NODE, "AGENT_COMPATIBILITY",
                    inputSummary, runId + ':' + COMPATIBILITY_NODE + ":1");
            nodeRunId = nodeClaim.getNodeRunId();
            events.publish(tenantId, workspaceId, runId, "node.started",
                    new RunLifecycleEventPayload(runId, RunStatus.RUNNING, COMPATIBILITY_NODE,
                            null, "compatibility node started"), false);
            if (!nodeClaim.isClaimed()) {
                AgentRunRecord current = runs.find(tenantId, workspaceId, runId).orElse(run);
                if (current.getStatus() == RunStatus.CANCELLED) return;
                events.publish(tenantId, workspaceId, runId, "node.completed",
                        new RunLifecycleEventPayload(runId, RunStatus.RUNNING, COMPATIBILITY_NODE,
                                null, "idempotent node output restored"), false);
                runs.complete(tenantId, workspaceId, runId, nodeClaim.getOutputJson());
                events.publish(tenantId, workspaceId, runId, "run.completed",
                        new RunLifecycleEventPayload(runId, RunStatus.COMPLETED, null, null,
                                "run completed from persisted node output"), true);
                return;
            }
            AgentRunOutput output = executionEngine.execute(definition, context, input);
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
                    new RunLifecycleEventPayload(runId, RunStatus.RUNNING, COMPATIBILITY_NODE,
                            null, "compatibility node completed"), false);
            runs.complete(tenantId, workspaceId, runId, outputJson);
            events.publish(tenantId, workspaceId, runId, "run.completed",
                    new RunLifecycleEventPayload(runId, RunStatus.COMPLETED, null, null,
                            "run completed"), true);
        } catch (Exception e) {
            fail(tenantId, workspaceId, runId, nodeRunId, e);
        }
    }

    private void timeout(AgentRunRecord run, String nodeRunId) {
        if (nodeRunId != null) {
            nodeRuns.fail(run.getTenantId(), run.getWorkspaceId(), nodeRunId, NodeRunStatus.TIMED_OUT,
                    "RUN_DEADLINE_EXCEEDED", "run deadline exceeded", false);
        }
        runs.fail(run.getTenantId(), run.getWorkspaceId(), run.getId(), "RUN_DEADLINE_EXCEEDED",
                "run deadline exceeded", RunStatus.TIMED_OUT);
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
        try {
            events.publish(tenantId, workspaceId, runId, "run.failed",
                    new RunLifecycleEventPayload(runId, RunStatus.FAILED, null, code, message), true);
        } catch (Exception eventError) {
            log.error("failed to persist terminal run event, runId={}", runId, eventError);
        }
        log.error("agent run failed, runId={}, errorCode={}", runId, code, error);
    }

    private String errorCode(Exception error) {
        if (error instanceof AiPlatformException) {
            return ((AiPlatformException) error).getErrorCode().name();
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "AGENT_EXECUTION_TIMEOUT";
        }
        return ErrorCode.AI_EXECUTION_FAILED.name();
    }
}
