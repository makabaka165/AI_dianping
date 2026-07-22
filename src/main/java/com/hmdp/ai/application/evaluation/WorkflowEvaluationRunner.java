package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowRepository;
import com.hmdp.ai.domain.workflow.WorkflowValidator;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class WorkflowEvaluationRunner implements EvaluationTargetRunner {
    private final AgentDefinitionLoader definitions;
    private final WorkflowRepository workflows;
    private final EvaluationWorkflowRunner runtime;
    private final WorkflowValidator validator;
    private final EvaluationRunSupport runs;
    private final ObjectMapper mapper;

    public WorkflowEvaluationRunner(AgentDefinitionLoader definitions, WorkflowRepository workflows,
                                    EvaluationWorkflowRunner runtime, WorkflowValidator validator,
                                    EvaluationRunSupport runs, ObjectMapper mapper) {
        this.definitions = definitions;
        this.workflows = workflows;
        this.runtime = runtime;
        this.validator = validator;
        this.runs = runs;
        this.mapper = mapper;
    }

    @Override
    public String targetType() { return "WORKFLOW"; }

    @Override
    public EvaluationExecutionResult execute(EvaluationTargetRequest request) {
        try {
            int workflowVersion = request.targetVersionOr(1);
            WorkflowDefinition workflow = workflows.findVersionNumber(request.getTenantId(),
                            request.getWorkspaceId(), request.getTargetId(), workflowVersion)
                    .orElseGet(() -> workflows.findVersion(request.getTenantId(), request.getWorkspaceId(),
                            request.getTargetId()).orElseThrow(() ->
                            new IllegalArgumentException("EVALUATION_WORKFLOW_NOT_FOUND")));
            if (!validator.validate(workflow).isValid()) {
                throw new IllegalArgumentException("EVALUATION_WORKFLOW_INVALID");
            }
            String agentId = request.getOptions().getAgentId();
            if (agentId == null || agentId.trim().isEmpty()) {
                throw new IllegalArgumentException("EVALUATION_WORKFLOW_AGENT_REQUIRED");
            }
            int agentVersion = request.getOptions().getAgentVersion() == null
                    ? 1 : request.getOptions().getAgentVersion();
            PublishedAgentDefinition definition = definitions.load(request.getTenantId(),
                    request.getWorkspaceId(), agentId, agentVersion);
            AgentInputRequest input = mapper.readValue(request.getEvaluationCase().getInputJson(),
                    AgentInputRequest.class);
            EvaluationRunSupport.EvaluationRunDescriptor descriptor = new EvaluationRunSupport
                    .EvaluationRunDescriptor(definition.getAgent().getId(), definition.getVersion().getVersion(),
                    mapper.writeValueAsString(definition.getVersionSnapshot()),
                    workflow.getExecutionPolicyJson(), Collections.emptyList(), input.getReferenceUris(),
                    "zh-CN", "Asia/Shanghai");
            return runs.execute(request, descriptor, session -> {
                AgentRunOutput output = runtime.execute(workflow, definition, session.getContext(), input);
                UsageSummary usage = output.getUsage() == null ? UsageSummary.empty(0) : output.getUsage();
                return EvaluationTargetOutput.success(mapper.valueToTree(output), usage.getInputTokens(),
                        usage.getOutputTokens(), usage.getModelCalls(), usage.getToolCalls(),
                        java.math.BigDecimal.ZERO);
            });
        } catch (Exception error) {
            String message = error.getMessage();
            String code = message != null && message.matches("[A-Z0-9_]{3,64}")
                    ? message : "EVALUATION_TARGET_FAILED";
            return new EvaluationExecutionResult(null, mapper.createObjectNode().put("success", false)
                    .put("errorCode", code).put("errorMessage", message == null ? "target execution failed" : message),
                    0, 0, 0, 0, 0, 0, false, code, message);
        }
    }
}
