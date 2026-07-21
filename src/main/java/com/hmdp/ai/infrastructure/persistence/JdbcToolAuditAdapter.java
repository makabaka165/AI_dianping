package com.hmdp.ai.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.tool.ToolAuditDetails;
import com.hmdp.ai.domain.tool.ToolAuditPort;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolResult;
import com.hmdp.ai.guard.PiiRedactionService;
import com.hmdp.ai.infra.AiLogSanitizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;

@Repository
public class JdbcToolAuditAdapter implements ToolAuditPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PiiRedactionService redactor;

    public JdbcToolAuditAdapter(JdbcTemplate jdbc, ObjectMapper mapper, PiiRedactionService redactor) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.redactor = redactor;
    }

    @Override
    public void record(ToolDefinition definition, ToolInvocation invocation, ToolResult result,
                       long startedAtMillis, long durationMillis) {
        ToolAuditDetails details = result.getAuditDetails();
        int timeoutMs = details == null ? definition.getTimeoutMs() : details.getTimeoutMs();
        long resultSize = details == null ? 0 : details.getResultSizeBytes();
        String validation = details == null ? "UNKNOWN" : details.getInputSchemaValidationResult();
        String approvalId = details == null ? invocation.getApprovalRequestId() : details.getApprovalRequestId();
        int retries = details == null ? 0 : details.getRetryCount();
        String breaker = details == null ? "UNKNOWN" : details.getCircuitBreakerState();
        String artifacts = details == null ? json(Collections.emptyList()) : json(details.getArtifactIds());
        String citations = details == null ? json(Collections.emptyList()) : json(details.getCitationIds());
        jdbc.update("insert into ai_tool_call (id,tenant_id,workspace_id,run_id,node_run_id,invocation_id," +
                        "tool_id,tool_version,protocol,invocation_summary,result_summary," +
                        "input_schema_validation,approval_request_id,retry_count,circuit_breaker_state," +
                        "timeout_ms,result_size_bytes,artifact_ids_json,citation_ids_json,latency_ms,status," +
                        "retryable,error_code,started_at,finished_at,created_by,updated_by) " +
                        "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                invocation.getCallId(), invocation.getContext().getTenantId(),
                invocation.getContext().getWorkspaceId(), invocation.getContext().getRunId(),
                invocation.getNodeRunId(), invocation.getCallId(), definition.getId(), definition.getVersion(),
                definition.getProtocol().name(), summary(invocation.getInput()),
                summary(result.getData() == null ? result.getErrorMessage() : result.getData().toString()),
                validation, approvalId, retries, breaker, timeoutMs, resultSize, artifacts, citations,
                durationMillis, result.getStatus().name(), result.isRetryable(), result.getErrorCode(),
                Timestamp.from(Instant.ofEpochMilli(startedAtMillis)),
                Timestamp.from(Instant.ofEpochMilli(startedAtMillis + durationMillis)),
                invocation.getContext().getUserId(), invocation.getContext().getUserId());
    }

    private String summary(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return AiLogSanitizer.safe(redactor.redact(text), 1000);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TOOL_AUDIT_SERIALIZATION_FAILED", e);
        }
    }
}
