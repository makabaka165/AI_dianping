package com.hmdp.ai.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.approval.ApprovalRequest;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.ai.application.security.AiAuthorizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Component
public class ApprovalService {
    private final JdbcTemplate jdbc;
    private final AiIdGenerator ids;
    private final ContentHashService hashes;
    private final AiAuthorizationService authorization;

    public ApprovalService(JdbcTemplate jdbc, AiIdGenerator ids, ContentHashService hashes,
                           AiAuthorizationService authorization) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.hashes = hashes;
        this.authorization = authorization;
    }

    public ApprovalRequest request(ToolDefinition definition, ToolInvocation invocation, JsonNode input) {
        String inputHash = hashes.sha256(input == null ? "null" : input.toString());
        String id = ids.nextId();
        Instant expires = Instant.now().plusSeconds(600);
        jdbc.update("insert into ai_approval_request (id,tenant_id,workspace_id,run_id,node_run_id,tool_call_id,tool_id,tool_version,risk_level,input_hash,input_summary,requested_by,status,expires_at,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, invocation.getContext().getTenantId(), invocation.getContext().getWorkspaceId(),
                invocation.getContext().getRunId(), invocation.getNodeRunId(), invocation.getCallId(),
                definition.getId(), definition.getVersion(), definition.getRiskLevel().name(), inputHash,
                AiLogSanitizer.safe(String.valueOf(input), 1000), invocation.getContext().getUserId(),
                "PENDING", Timestamp.from(expires), invocation.getContext().getUserId(),
                invocation.getContext().getUserId());
        return new ApprovalRequest(id, inputHash, invocation.getContext().getUserId(), expires);
    }

    public boolean approved(ToolInvocation invocation, JsonNode input) {
        String inputHash = hashes.sha256(input == null ? "null" : input.toString());
        if (!authorization.authorize(invocation.getContext().getUserId(), invocation.getContext().getTenantId(),
                invocation.getContext().getWorkspaceId()).has(AiPermission.TOOL_APPROVE)) return false;
        Integer count = jdbc.queryForObject("select count(1) from ai_approval_request r join ai_approval_decision d on d.approval_request_id=r.id and d.decision='APPROVED' and d.deleted=0 where r.id=? and r.tenant_id=? and r.workspace_id=? and r.run_id=? and r.node_run_id=? and r.input_hash=? and r.requested_by<>d.decided_by and r.status='APPROVED' and r.expires_at>? and r.deleted=0",
                Integer.class, invocation.getApprovalRequestId(), invocation.getContext().getTenantId(),
                invocation.getContext().getWorkspaceId(), invocation.getContext().getRunId(),
                invocation.getNodeRunId(), inputHash, Timestamp.from(Instant.now()));
        return count != null && count > 0;
    }

    public Optional<ApprovalRequest> find(String tenantId, String workspaceId, String id) {
        return jdbc.query("select id,input_hash,requested_by,expires_at from ai_approval_request where tenant_id=? and workspace_id=? and id=? and deleted=0",
                (rs, row) -> new ApprovalRequest(rs.getString("id"), rs.getString("input_hash"),
                        rs.getString("requested_by"), rs.getTimestamp("expires_at").toInstant()),
                tenantId, workspaceId, id).stream().findFirst();
    }
}
