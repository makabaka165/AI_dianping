package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.application.dto.evaluation.EvaluationExecutionOptions;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowRepository;
import com.hmdp.ai.domain.workflow.WorkflowValidator;
import com.hmdp.ai.runtime.workflow.WorkflowRuntime;
import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;

@Component
public class EvaluationExecutor {
    private final AgentDefinitionLoader definitions;
    private final WorkflowRepository workflows;
    private final WorkflowRuntime runtime;
    private final WorkflowValidator validator;
    private final ObjectMapper mapper;
    private final AiIdGenerator ids;

    public EvaluationExecutor(AgentDefinitionLoader definitions, WorkflowRepository workflows,
                              WorkflowRuntime runtime, WorkflowValidator validator, ObjectMapper mapper,
                              AiIdGenerator ids) {
        this.definitions = definitions;
        this.workflows = workflows;
        this.runtime = runtime;
        this.validator = validator;
        this.mapper = mapper;
        this.ids = ids;
    }

    public EvaluationExecutionResult execute(EvaluationCase evaluationCase, String targetType, String targetId,
                                             Integer targetVersion, EvaluationExecutionOptions options,
                                             String tenantId, String workspaceId, String actorId) {
        long started = System.currentTimeMillis();
        String runId = ids.nextId();
        try {
            if (!"AGENT".equalsIgnoreCase(targetType) && !"WORKFLOW".equalsIgnoreCase(targetType)) {
                throw new IllegalArgumentException("EVALUATION_TARGET_UNSUPPORTED");
            }
            PublishedAgentDefinition definition = definitions.load(tenantId, workspaceId, targetId,
                    targetVersion == null ? 1 : targetVersion);
            WorkflowDefinition workflow = workflows.findVersion(tenantId, workspaceId,
                            definition.getVersion().getWorkflowVersionId())
                    .orElseThrow(() -> new IllegalArgumentException("EVALUATION_WORKFLOW_NOT_FOUND"));
            if (!validator.validate(workflow).isValid()) throw new IllegalArgumentException("EVALUATION_WORKFLOW_INVALID");
            AgentInputRequest input = mapper.readValue(evaluationCase.getInputJson(), AgentInputRequest.class);
            ExecutionContext context = new ExecutionContext(tenantId, workspaceId, actorId,
                    "evaluation-" + runId, runId, runId, definition.getAgent().getId(), definition.getVersion().getVersion(),
                    "zh-CN", "Asia/Shanghai", Collections.emptyList(), Collections.emptyList(),
                    new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN, AiPermission.KNOWLEDGE_READ,
                            AiPermission.MEMORY_READ)), ExecutionBudget.defaults(), Instant.now().plusSeconds(300),
                    Collections.emptyMap(), "evaluation-" + runId);
            AgentRunOutput output = runtime.execute(workflow, definition, context, input);
            JsonNode actual = mapper.valueToTree(output);
            if (actual.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) actual).put("runId", runId);
            long elapsed = System.currentTimeMillis() - started;
            return new EvaluationExecutionResult(runId, actual, elapsed, output.getUsage().getInputTokens(),
                    output.getUsage().getOutputTokens(), 1, 0, 0, output.getStatus() == RunStatus.COMPLETED);
        } catch (Exception error) {
            com.fasterxml.jackson.databind.node.ObjectNode actual = mapper.createObjectNode()
                    .put("runId", runId).put("success", false).put("errorCode", error.getMessage());
            return new EvaluationExecutionResult(runId, actual, System.currentTimeMillis() - started,
                    0, 0, 0, 0, 0, false);
        }
    }
}
