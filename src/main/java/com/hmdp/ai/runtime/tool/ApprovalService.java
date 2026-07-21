package com.hmdp.ai.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.approval.ApprovalRequest;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ApprovalService {
  private final JdbcTemplate jdbc;
  private final AiIdGenerator ids;
  private final ContentHashService hashes;

  public ApprovalService(JdbcTemplate jdbc, AiIdGenerator ids, ContentHashService hashes) {
    this.jdbc = jdbc;
    this.ids = ids;
    this.hashes = hashes;
  }

  public ApprovalRequest request(
      ToolDefinition definition, ToolInvocation invocation, JsonNode input) {
    String inputHash = hashes.sha256(input == null ? "null" : input.toString());
    String id = ids.nextId();
    Instant expires = Instant.now().plusSeconds(600);
    jdbc.update(
        "insert into ai_approval_request (id,tenant_id,workspace_id,run_id,node_run_id,tool_call_id,tool_id,tool_version,risk_level,input_hash,input_summary,requested_by,status,expires_at,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        invocation.getContext().getTenantId(),
        invocation.getContext().getWorkspaceId(),
        invocation.getContext().getRunId(),
        invocation.getNodeRunId(),
        invocation.getCallId(),
        definition.getId(),
        definition.getVersion(),
        definition.getRiskLevel().name(),
        inputHash,
        AiLogSanitizer.safe(String.valueOf(input), 1000),
        invocation.getContext().getUserId(),
        "PENDING",
        Timestamp.from(expires),
        invocation.getContext().getUserId(),
        invocation.getContext().getUserId());
    return new ApprovalRequest(id, inputHash, invocation.getContext().getUserId(), expires);
  }

  /**
   * Approval is made by a separate principal. The run principal still has to pass the normal tool
   * permission check, but must not also hold TOOL_APPROVE. The node run id is intentionally not
   * part of the lookup: resuming a waiting node creates a fresh node-run record. The immutable
   * input hash, run, tool and version bind the decision to the exact call.
   */
  public boolean approved(ToolDefinition definition, ToolInvocation invocation, JsonNode input) {
    String inputHash = hashes.sha256(input == null ? "null" : input.toString());
    Integer count =
        jdbc.queryForObject(
            "select count(1) from ai_approval_request r join ai_approval_decision d "
                + "on d.approval_request_id=r.id and d.decision='APPROVED' and d.deleted=0 "
                + "where r.id=? and r.tenant_id=? and r.workspace_id=? and r.run_id=? "
                + "and r.tool_id=? and r.tool_version=? and r.input_hash=? "
                + "and r.requested_by<>d.decided_by and r.status='APPROVED' "
                + "and r.expires_at>? and r.deleted=0",
            Integer.class,
            invocation.getApprovalRequestId(),
            invocation.getContext().getTenantId(),
            invocation.getContext().getWorkspaceId(),
            invocation.getContext().getRunId(),
            definition.getId(),
            definition.getVersion(),
            inputHash,
            Timestamp.from(Instant.now()));
    return count != null && count > 0;
  }

  public Optional<ApprovalRequest> find(String tenantId, String workspaceId, String id) {
    return jdbc
        .query(
            "select id,input_hash,requested_by,expires_at from ai_approval_request where tenant_id=? and workspace_id=? and id=? and deleted=0",
            (rs, row) ->
                new ApprovalRequest(
                    rs.getString("id"),
                    rs.getString("input_hash"),
                    rs.getString("requested_by"),
                    rs.getTimestamp("expires_at").toInstant()),
            tenantId,
            workspaceId,
            id)
        .stream()
        .findFirst();
  }
}
