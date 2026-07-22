package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.AttachmentReference;
import com.hmdp.ai.domain.run.UsageSummary;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowRepository;
import com.hmdp.ai.domain.workflow.WorkflowValidator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class AgentEvaluationRunner implements EvaluationTargetRunner {
    private final AgentDefinitionLoader definitions;
    private final WorkflowRepository workflows;
    private final EvaluationWorkflowRunner runtime;
    private final WorkflowValidator validator;
    private final EvaluationRunSupport runs;
    private final ObjectMapper mapper;

    public AgentEvaluationRunner(AgentDefinitionLoader definitions, WorkflowRepository workflows,
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
    public String targetType() { return "AGENT"; }

    @Override
    public EvaluationExecutionResult execute(EvaluationTargetRequest request) {
        try {
            int version = request.targetVersionOr(1);
            PublishedAgentDefinition definition = definitions.load(request.getTenantId(),
                    request.getWorkspaceId(), request.getTargetId(), version);
            WorkflowDefinition workflow = workflows.findVersion(request.getTenantId(),
                            request.getWorkspaceId(), definition.getVersion().getWorkflowVersionId())
                    .orElseThrow(() -> new IllegalArgumentException("EVALUATION_WORKFLOW_NOT_FOUND"));
            requireValid(workflow);
            AgentInputRequest input = mapper.readValue(request.getEvaluationCase().getInputJson(),
                    AgentInputRequest.class);
            EvaluationRunSupport.EvaluationRunDescriptor descriptor = descriptor(definition, input);
            return runs.execute(request, descriptor, session -> {
                AgentRunOutput output = runtime.execute(workflow, definition, session.getContext(), input);
                UsageSummary usage = output.getUsage() == null ? UsageSummary.empty(0) : output.getUsage();
                return EvaluationTargetOutput.success(mapper.valueToTree(output), usage.getInputTokens(),
                        usage.getOutputTokens(), usage.getModelCalls(), usage.getToolCalls(),
                        java.math.BigDecimal.ZERO);
            });
        } catch (Exception error) {
            return failure(error);
        }
    }

    protected EvaluationRunSupport.EvaluationRunDescriptor descriptor(PublishedAgentDefinition definition,
                                                                        AgentInputRequest input) {
        try {
            List<AttachmentReference> attachments = new ArrayList<>();
            input.getAttachments().forEach(value -> attachments.add(new AttachmentReference(
                    value.getAttachmentId(), value.getName(), value.getContentType(),
                    value.getSizeBytes(), value.getUri())));
            return new EvaluationRunSupport.EvaluationRunDescriptor(definition.getAgent().getId(),
                    definition.getVersion().getVersion(), mapper.writeValueAsString(
                            definition.getVersionSnapshot()), definition.getVersion().getExecutionPolicyJson(),
                    attachments, input.getReferenceUris(), "zh-CN", "Asia/Shanghai");
        } catch (Exception error) {
            throw new IllegalStateException("EVALUATION_VERSION_SNAPSHOT_INVALID", error);
        }
    }

    protected void requireValid(WorkflowDefinition workflow) {
        if (!validator.validate(workflow).isValid()) {
            throw new IllegalArgumentException("EVALUATION_WORKFLOW_INVALID");
        }
    }

    protected EvaluationExecutionResult failure(Exception error) {
        String message = error.getMessage();
        String code = message != null && message.matches("[A-Z0-9_]{3,64}")
                ? message : "EVALUATION_TARGET_FAILED";
        com.fasterxml.jackson.databind.node.ObjectNode actual = mapper.createObjectNode()
                .put("success", false).put("errorCode", code).put("errorMessage",
                        message == null ? "target execution failed" : message);
        return new EvaluationExecutionResult(null, actual, 0, 0, 0, 0, 0, 0,
                false, code, message);
    }
}
