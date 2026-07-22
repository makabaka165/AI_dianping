package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.observability.RunInspectionPort;
import com.hmdp.ai.domain.observability.RunUsageSummary;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.AttachmentReference;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class EvaluationRunSupport {
    private final RunRepository runs;
    private final RunInspectionPort inspection;
    private final ExecutionBudgetFactory budgets;
    private final AiIdGenerator ids;
    private final ObjectMapper mapper;

    public EvaluationRunSupport(RunRepository runs, RunInspectionPort inspection,
                                ExecutionBudgetFactory budgets, AiIdGenerator ids,
                                ObjectMapper mapper) {
        this.runs = runs;
        this.inspection = inspection;
        this.budgets = budgets;
        this.ids = ids;
        this.mapper = mapper;
    }

    public EvaluationExecutionResult execute(EvaluationTargetRequest request,
                                             EvaluationRunDescriptor descriptor,
                                             TargetOperation operation) {
        long started = System.currentTimeMillis();
        String runId = ids.nextId();
        boolean created = false;
        try {
            ExecutionBudget budget = budgets.fromPolicy(descriptor.getExecutionPolicyJson());
            Instant now = Instant.now();
            AgentRunRecord run = new AgentRunRecord(runId, request.getTenantId(), request.getWorkspaceId(),
                    request.getActorId(), "evaluation-" + runId, "evaluation-" + runId,
                    descriptor.getAgentId(), descriptor.getAgentVersion(), RunStatus.QUEUED, "BLOCKING",
                    request.getEvaluationCase().getInputJson(), null, metadata(request),
                    descriptor.getVersionSnapshotJson(), budgets.snapshotJson(budget),
                    authorizationJson(request), ids.nextId(), null, 1, null, null, now, null, null,
                    now.plus(budget.getMaxRunDuration()), null);
            runs.create(run);
            created = true;
            if (!runs.claimQueued(request.getTenantId(), request.getWorkspaceId(), runId)) {
                throw new IllegalStateException("EVALUATION_RUN_CLAIM_FAILED");
            }
            ExecutionContext context = new ExecutionContext(request.getTenantId(), request.getWorkspaceId(),
                    request.getActorId(), run.getSessionId(), run.getConversationId(), runId,
                    descriptor.getAgentId(), descriptor.getAgentVersion(), descriptor.getLocale(),
                    descriptor.getTimezone(), descriptor.getAttachments(), descriptor.getReferenceUris(),
                    request.getAuthorization(), budget, run.getDeadlineAt(), Collections.emptyMap(),
                    run.getTraceId());
            EvaluationRunSession session = new EvaluationRunSession(run, context,
                    runId + ":evaluation-node", ids.nextId());
            EvaluationTargetOutput output = operation.execute(session);
            JsonNode actual = withRunId(output.getActual(), runId);
            long elapsed = System.currentTimeMillis() - started;
            if (!output.isSuccess()) {
                String code = valueOr(output.getErrorCode(), "EVALUATION_TARGET_FAILED");
                String message = valueOr(output.getErrorMessage(), "target execution failed");
                runs.fail(request.getTenantId(), request.getWorkspaceId(), runId, code,
                        limit(message, 1000), RunStatus.FAILED);
                return new EvaluationExecutionResult(runId, actual, elapsed, output.getInputTokens(),
                        output.getOutputTokens(), output.getModelCalls(), output.getToolCalls(),
                        output.getCost().doubleValue(), false, code, limit(message, 1000));
            }
            runs.complete(request.getTenantId(), request.getWorkspaceId(), runId, json(actual));
            Usage usage = usage(request, runId, output);
            return new EvaluationExecutionResult(runId, actual, elapsed, usage.inputTokens,
                    usage.outputTokens, usage.modelCalls, usage.toolCalls, usage.cost.doubleValue(), true);
        } catch (Exception error) {
            String code = errorCode(error);
            String message = safeMessage(error);
            if (created) {
                try {
                    runs.fail(request.getTenantId(), request.getWorkspaceId(), runId, code, message,
                            RunStatus.FAILED);
                } catch (RuntimeException ignored) {
                    // Preserve the target execution failure as the evaluation result.
                }
            }
            ObjectNode actual = mapper.createObjectNode().put("runId", runId).put("success", false)
                    .put("errorCode", code).put("errorMessage", message);
            return new EvaluationExecutionResult(runId, actual, System.currentTimeMillis() - started,
                    0, 0, 0, 0, 0, false, code, message);
        }
    }

    private Usage usage(EvaluationTargetRequest request, String runId, EvaluationTargetOutput output) {
        if (!request.getOptions().isCaptureTrace()) return Usage.from(output);
        try {
            RunUsageSummary recorded = inspection.usage(request.getTenantId(), request.getWorkspaceId(), runId);
            if (recorded.getModelCalls() == 0 && recorded.getToolCalls() == 0) return Usage.from(output);
            return new Usage(recorded.getInputTokens(), recorded.getOutputTokens(),
                    Math.toIntExact(recorded.getModelCalls()), Math.toIntExact(recorded.getToolCalls()),
                    recorded.getTotalCost() == null ? output.getCost() : recorded.getTotalCost());
        } catch (RuntimeException error) {
            return Usage.from(output);
        }
    }

    private JsonNode withRunId(JsonNode value, String runId) {
        JsonNode actual = value == null ? mapper.createObjectNode() : value.deepCopy();
        if (actual.isObject()) {
            ((ObjectNode) actual).put("runId", runId);
            return actual;
        }
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.set("output", actual);
        wrapped.put("runId", runId);
        return wrapped;
    }

    private String metadata(EvaluationTargetRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evaluation", true);
        metadata.put("evaluationCaseId", request.getEvaluationCase().getId());
        metadata.put("evaluationTargetType", request.getTargetType().toUpperCase(java.util.Locale.ROOT));
        metadata.put("evaluationTargetId", request.getTargetId());
        metadata.put("evaluationTargetVersion", request.getTargetVersion());
        return json(metadata);
    }

    private String authorizationJson(EvaluationTargetRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("permissions", request.getAuthorization().getPermissions().stream()
                .map(Enum::name).sorted().collect(Collectors.toCollection(ArrayList::new)));
        return json(snapshot);
    }

    private String errorCode(Exception error) {
        String message = error.getMessage();
        if (message != null && message.matches("[A-Z0-9_]{3,64}")) return message;
        return "EVALUATION_TARGET_FAILED";
    }

    private String safeMessage(Exception error) {
        return limit(valueOr(error.getMessage(), "target execution failed"), 1000);
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("evaluation value cannot be serialized", error);
        }
    }

    @FunctionalInterface
    public interface TargetOperation {
        EvaluationTargetOutput execute(EvaluationRunSession session) throws Exception;
    }

    public static final class EvaluationRunSession {
        private final AgentRunRecord run;
        private final ExecutionContext context;
        private final String nodeRunId;
        private final String invocationId;

        private EvaluationRunSession(AgentRunRecord run, ExecutionContext context,
                                     String nodeRunId, String invocationId) {
            this.run = run;
            this.context = context;
            this.nodeRunId = nodeRunId;
            this.invocationId = invocationId;
        }

        public AgentRunRecord getRun() { return run; }
        public ExecutionContext getContext() { return context; }
        public String getNodeRunId() { return nodeRunId; }
        public String getInvocationId() { return invocationId; }
    }

    public static final class EvaluationRunDescriptor {
        private final String agentId;
        private final int agentVersion;
        private final String versionSnapshotJson;
        private final String executionPolicyJson;
        private final List<AttachmentReference> attachments;
        private final List<String> referenceUris;
        private final String locale;
        private final String timezone;

        public EvaluationRunDescriptor(String agentId, int agentVersion, String versionSnapshotJson,
                                       String executionPolicyJson, List<AttachmentReference> attachments,
                                       List<String> referenceUris, String locale, String timezone) {
            this.agentId = agentId;
            this.agentVersion = agentVersion;
            this.versionSnapshotJson = versionSnapshotJson == null ? "{}" : versionSnapshotJson;
            this.executionPolicyJson = executionPolicyJson == null ? "{}" : executionPolicyJson;
            this.attachments = immutable(attachments);
            this.referenceUris = immutable(referenceUris);
            this.locale = locale == null ? "zh-CN" : locale;
            this.timezone = timezone == null ? "Asia/Shanghai" : timezone;
        }

        private static <T> List<T> immutable(List<T> values) {
            return Collections.unmodifiableList(new ArrayList<>(values == null
                    ? Collections.emptyList() : values));
        }

        public String getAgentId() { return agentId; }
        public int getAgentVersion() { return agentVersion; }
        public String getVersionSnapshotJson() { return versionSnapshotJson; }
        public String getExecutionPolicyJson() { return executionPolicyJson; }
        public List<AttachmentReference> getAttachments() { return attachments; }
        public List<String> getReferenceUris() { return referenceUris; }
        public String getLocale() { return locale; }
        public String getTimezone() { return timezone; }
    }

    private static final class Usage {
        private final long inputTokens;
        private final long outputTokens;
        private final int modelCalls;
        private final int toolCalls;
        private final BigDecimal cost;

        private Usage(long inputTokens, long outputTokens, int modelCalls,
                      int toolCalls, BigDecimal cost) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.modelCalls = modelCalls;
            this.toolCalls = toolCalls;
            this.cost = cost == null ? BigDecimal.ZERO : cost;
        }

        private static Usage from(EvaluationTargetOutput output) {
            return new Usage(output.getInputTokens(), output.getOutputTokens(), output.getModelCalls(),
                    output.getToolCalls(), output.getCost());
        }
    }
}
