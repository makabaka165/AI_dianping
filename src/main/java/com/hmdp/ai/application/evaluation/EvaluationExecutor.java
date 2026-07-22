package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.dto.evaluation.EvaluationExecutionOptions;
import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.domain.observability.RunInspectionPort;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.workflow.WorkflowRepository;
import com.hmdp.ai.domain.workflow.WorkflowValidator;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;

@Component
public class EvaluationExecutor {
    private final EvaluationTargetResolver resolver;
    private final ObjectMapper mapper;

    @Autowired
    public EvaluationExecutor(EvaluationTargetResolver resolver, ObjectMapper mapper) {
        this.resolver = resolver;
        this.mapper = mapper;
    }

    /**
     * Compatibility constructor retained for callers that assembled the evaluator in tests.
     * Production wiring uses the resolver constructor above so every target runner is discovered.
     */
    public EvaluationExecutor(AgentDefinitionLoader definitions, WorkflowRepository workflows,
                              EvaluationWorkflowRunner runtime, WorkflowValidator validator,
                              ObjectMapper mapper, AiIdGenerator ids, RunRepository runs,
                              RunInspectionPort inspection, ExecutionBudgetFactory budgets) {
        this.mapper = mapper;
        EvaluationRunSupport support = new EvaluationRunSupport(runs, inspection, budgets, ids, mapper);
        AgentEvaluationRunner agent = new AgentEvaluationRunner(definitions, workflows, runtime,
                validator, support, mapper);
        WorkflowEvaluationRunner workflow = new WorkflowEvaluationRunner(definitions, workflows, runtime,
                validator, support, mapper);
        this.resolver = new EvaluationTargetResolver(java.util.Arrays.asList(agent, workflow));
    }

    public EvaluationExecutionResult execute(EvaluationCase evaluationCase, String targetType,
                                             String targetId, Integer targetVersion,
                                             EvaluationExecutionOptions options, String tenantId,
                                             String workspaceId, String actorId) {
        return execute(evaluationCase, targetType, targetId, targetVersion, options, tenantId,
                workspaceId, actorId, defaultAuthorization());
    }

    public EvaluationExecutionResult execute(EvaluationCase evaluationCase, String targetType,
                                             String targetId, Integer targetVersion,
                                             EvaluationExecutionOptions options, String tenantId,
                                             String workspaceId, String actorId,
                                             AuthorizationContext authorization) {
        EvaluationExecutionOptions effectiveOptions = options == null
                ? new EvaluationExecutionOptions() : options;
        AuthorizationContext effectiveAuthorization = authorization == null
                ? defaultAuthorization() : authorization;
        EvaluationTargetRequest request = new EvaluationTargetRequest(evaluationCase, targetType, targetId,
                targetVersion, effectiveOptions, tenantId, workspaceId, actorId, effectiveAuthorization);
        try {
            return resolver.resolve(targetType).execute(request);
        } catch (RuntimeException error) {
            String message = error.getMessage();
            String code = message != null && message.matches("[A-Z0-9_]{3,64}")
                    ? message : "EVALUATION_TARGET_FAILED";
            return new EvaluationExecutionResult(null, mapper.createObjectNode().put("success", false)
                    .put("errorCode", code).put("errorMessage",
                            message == null ? "target execution failed" : message),
                    0, 0, 0, 0, 0, 0, false, code, message);
        }
    }

    public boolean supports(String targetType) {
        try {
            resolver.resolve(targetType);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private AuthorizationContext defaultAuthorization() {
        return new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN,
                AiPermission.KNOWLEDGE_READ, AiPermission.MEMORY_READ,
                AiPermission.EVALUATION_RUN));
    }
}
