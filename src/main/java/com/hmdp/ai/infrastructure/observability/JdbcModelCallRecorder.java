package com.hmdp.ai.infrastructure.observability;

import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.observability.InvocationContext;
import com.hmdp.ai.guard.PiiRedactionService;
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.ai.runtime.model.ModelCallRecorder;
import com.hmdp.ai.runtime.model.ModelInvocation;
import com.hmdp.ai.runtime.model.ModelInvocationResult;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

@Component
public class JdbcModelCallRecorder implements ModelCallRecorder {
    private final JdbcTemplate jdbc;
    private final AiIdGenerator ids;
    private final PiiRedactionService redactor;

    public JdbcModelCallRecorder(JdbcTemplate jdbc, AiIdGenerator ids, PiiRedactionService redactor) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.redactor = redactor;
    }

    @Override
    public void success(ModelProfileVersion profile, ModelInvocation invocation, ModelInvocationResult result,
                        long startedAtMillis) {
        insert(profile, invocation, "SUCCEEDED", null, summary(invocation.getRequestSummary()),
                summary(result.getContent()), result.getInputTokens(), result.getOutputTokens(),
                result.isEstimatedUsage(), result.getEstimatedCost(), startedAtMillis, result.getLatencyMs());
    }

    @Override
    public void failure(ModelProfileVersion profile, ModelInvocation invocation, String errorCode,
                        long startedAtMillis, long latencyMs) {
        insert(profile, invocation, "FAILED", safeCode(errorCode), summary(invocation.getRequestSummary()),
                null, 0, 0, true, BigDecimal.ZERO, startedAtMillis, latencyMs);
    }

    private void insert(ModelProfileVersion profile, ModelInvocation invocation, String status, String errorCode,
                        String requestSummary, String responseSummary, long inputTokens, long outputTokens,
                        boolean estimated, BigDecimal cost, long startedAtMillis, long latencyMs) {
        InvocationContext context = invocation.getContext().getInvocationContext();
        jdbc.update("insert into ai_model_call (id,tenant_id,workspace_id,run_id,node_run_id,invocation_id," +
                        "model_profile_id,model_profile_version_id,model_profile_revision,provider,model_name," +
                        "operation,request_summary,response_summary,input_tokens,output_tokens,estimated_usage," +
                        "estimated_cost,latency_ms,status,error_code,started_at,finished_at,created_by,updated_by) " +
                        "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                ids.nextId(), context.getTenantId(), context.getWorkspaceId(), context.getRunId(),
                context.getNodeRunId(), context.getInvocationId(), profile.getModelProfileId(), profile.getId(),
                profile.getVersion(), profile.getProvider(), profile.getModelName(), "CHAT",
                requestSummary, responseSummary, inputTokens, outputTokens, estimated, cost, latencyMs, status,
                errorCode, Timestamp.from(Instant.ofEpochMilli(startedAtMillis)), Timestamp.from(Instant.now()),
                context.getUserId(), context.getUserId());
    }

    private String summary(String value) {
        return AiLogSanitizer.safe(redactor.redact(value), 1000);
    }

    private String safeCode(String value) {
        String safe = AiLogSanitizer.safe(value, 64);
        return safe == null || safe.trim().isEmpty() ? "MODEL_INVOCATION_FAILED" : safe;
    }
}
