package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.application.dto.evaluation.EvaluationExecutionOptions;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.domain.observability.RunInspectionPort;
import com.hmdp.ai.domain.observability.RunUsageSummary;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.AttachmentReference;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowRepository;
import com.hmdp.ai.domain.workflow.WorkflowValidator;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class EvaluationExecutor {
    private final AgentDefinitionLoader definitions;
    private final WorkflowRepository workflows;
    private final EvaluationWorkflowRunner runtime;
    private final WorkflowValidator validator;
    private final ObjectMapper mapper;
    private final AiIdGenerator ids;
    private final RunRepository runs;
    private final RunInspectionPort inspection;
    private final ExecutionBudgetFactory budgets;

    public EvaluationExecutor(AgentDefinitionLoader definitions, WorkflowRepository workflows,
                              EvaluationWorkflowRunner runtime, WorkflowValidator validator, ObjectMapper mapper,
                              AiIdGenerator ids, RunRepository runs, RunInspectionPort inspection,
                              ExecutionBudgetFactory budgets) {
        this.definitions = definitions;
        this.workflows = workflows;
        this.runtime = runtime;
        this.validator = validator;
        this.mapper = mapper;
        this.ids = ids;
        this.runs = runs;
        this.inspection = inspection;
        this.budgets = budgets;
    }

    public EvaluationExecutionResult execute(EvaluationCase evaluationCase, String targetType, String targetId,
                                             Integer targetVersion, EvaluationExecutionOptions options,
                                             String tenantId, String workspaceId, String actorId) {
        return execute(evaluationCase, targetType, targetId, targetVersion, options, tenantId, workspaceId,
                actorId, new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN,
                AiPermission.KNOWLEDGE_READ, AiPermission.MEMORY_READ, AiPermission.EVALUATION_RUN)));
    }

    public EvaluationExecutionResult execute(EvaluationCase evaluationCase, String targetType, String targetId,
                                             Integer targetVersion, EvaluationExecutionOptions options,
                                             String tenantId, String workspaceId, String actorId,
                                             AuthorizationContext authorization) {
        long started = System.currentTimeMillis();
        String runId = ids.nextId();
        boolean runCreated = false;
        try {
            if (!"AGENT".equalsIgnoreCase(targetType) && !"WORKFLOW".equalsIgnoreCase(targetType)) {
                throw new IllegalArgumentException("EVALUATION_TARGET_UNSUPPORTED");
            }
            PublishedAgentDefinition definition = definitions.load(tenantId, workspaceId, targetId,
                    targetVersion == null ? 1 : targetVersion);
            WorkflowDefinition workflow = workflows.findVersion(tenantId, workspaceId,
                            definition.getVersion().getWorkflowVersionId())
                    .orElseThrow(() -> new IllegalArgumentException("EVALUATION_WORKFLOW_NOT_FOUND"));
            if (!validator.validate(workflow).isValid()) {
                throw new IllegalArgumentException("EVALUATION_WORKFLOW_INVALID");
            }
            AgentInputRequest input = mapper.readValue(evaluationCase.getInputJson(), AgentInputRequest.class);
            ExecutionBudget budget = budgets.fromPolicy(definition.getVersion().getExecutionPolicyJson());
            Instant now = Instant.now();
            AuthorizationContext currentAuthorization = authorization == null
                    ? new AuthorizationContext(EnumSet.noneOf(AiPermission.class)) : authorization;
            AgentRunRecord run = new AgentRunRecord(runId, tenantId, workspaceId, actorId,
                    "evaluation-" + runId, "evaluation-" + runId, definition.getAgent().getId(),
                    definition.getVersion().getVersion(), RunStatus.QUEUED, "BLOCKING",
                    evaluationCase.getInputJson(), null, metadata(evaluationCase, targetType),
                    json(definition.getVersionSnapshot()), budgets.snapshotJson(budget),
                    authorizationJson(currentAuthorization), ids.nextId(), null, 1, null, null,
                    now, null, null, now.plus(budget.getMaxRunDuration()), null);
            runs.create(run);
            runCreated = true;
            if (!runs.claimQueued(tenantId, workspaceId, runId)) {
                throw new IllegalStateException("EVALUATION_RUN_CLAIM_FAILED");
            }
            List<AttachmentReference> attachments = evaluationAttachments(input);
            ExecutionContext context = new ExecutionContext(tenantId, workspaceId, actorId,
                    run.getSessionId(), run.getConversationId(), runId, definition.getAgent().getId(),
                    definition.getVersion().getVersion(), "zh-CN", "Asia/Shanghai", attachments,
                    input.getReferenceUris(),
                    currentAuthorization, budget, run.getDeadlineAt(), Collections.emptyMap(), run.getTraceId());
            AgentRunOutput output = runtime.execute(workflow, definition, context, input);
            String outputJson = json(output);
            runs.complete(tenantId, workspaceId, runId, outputJson);
            JsonNode actual = mapper.valueToTree(output);
            if (actual.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) actual).put("runId", runId);
            long elapsed = System.currentTimeMillis() - started;
            RunUsageSummary usage = inspection.usage(tenantId, workspaceId, runId);
            long inputTokens = usage.getModelCalls() > 0
                    ? usage.getInputTokens() : output.getUsage().getInputTokens();
            long outputTokens = usage.getModelCalls() > 0
                    ? usage.getOutputTokens() : output.getUsage().getOutputTokens();
            int modelCalls = Math.toIntExact(usage.getModelCalls() > 0
                    ? usage.getModelCalls() : output.getUsage().getModelCalls());
            int toolCalls = Math.toIntExact(usage.getToolCalls() > 0
                    ? usage.getToolCalls() : output.getUsage().getToolCalls());
            BigDecimal cost = usage.getTotalCost() == null ? BigDecimal.ZERO : usage.getTotalCost();
            return new EvaluationExecutionResult(runId, actual, elapsed, inputTokens, outputTokens,
                    modelCalls, toolCalls, cost.doubleValue(), output.getStatus() == RunStatus.COMPLETED);
        } catch (Exception error) {
            String errorCode = errorCode(error);
            String errorMessage = safeMessage(error);
            if (runCreated) {
                try {
                    runs.fail(tenantId, workspaceId, runId, errorCode, errorMessage, RunStatus.FAILED);
                } catch (RuntimeException ignored) {
                    // Preserve the target execution failure as the evaluation result.
                }
            }
            com.fasterxml.jackson.databind.node.ObjectNode actual = mapper.createObjectNode()
                    .put("runId", runId).put("success", false).put("errorCode", errorCode)
                    .put("errorMessage", errorMessage);
            return new EvaluationExecutionResult(runId, actual, System.currentTimeMillis() - started,
                    0, 0, 0, 0, 0, false, errorCode, errorMessage);
        }
    }

    private String metadata(EvaluationCase evaluationCase, String targetType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evaluation", true);
        metadata.put("evaluationCaseId", evaluationCase.getId());
        metadata.put("evaluationTargetType", targetType.toUpperCase(java.util.Locale.ROOT));
        return json(metadata);
    }

    private List<AttachmentReference> evaluationAttachments(AgentInputRequest input) {
        List<AttachmentReference> attachments = new ArrayList<>();
        input.getAttachments().forEach(attachment -> attachments.add(new AttachmentReference(
                attachment.getAttachmentId(), attachment.getName(), attachment.getContentType(),
                attachment.getSizeBytes(), attachment.getUri())));
        return attachments;
    }

    private String authorizationJson(AuthorizationContext authorization) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<String> permissions = authorization.getPermissions().stream().map(AiPermission::name)
                .sorted().collect(Collectors.toCollection(ArrayList::new));
        snapshot.put("permissions", permissions);
        return json(snapshot);
    }

    private String errorCode(Exception error) {
        String message = error.getMessage();
        if (message != null && message.matches("[A-Z0-9_]{3,64}")) return message;
        return "EVALUATION_TARGET_FAILED";
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "target execution failed";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("evaluation value cannot be serialized", error);
        }
    }
}
