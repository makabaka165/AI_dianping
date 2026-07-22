package com.hmdp.ai.application.approval;

import com.hmdp.ai.application.dto.approval.ApprovalResponse;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.observability.AiAuditPort;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.common.ErrorCode;
import com.hmdp.exception.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalApplicationService {
  private final JdbcTemplate jdbc;
  private final AiAccessGuard access;
  private final AiIdGenerator ids;
  private final AiAuditPort audit;

  public ApprovalApplicationService(
      JdbcTemplate jdbc, AiAccessGuard access, AiIdGenerator ids, AiAuditPort audit) {
    this.jdbc = jdbc;
    this.access = access;
    this.ids = ids;
    this.audit = audit;
  }

  public List<ApprovalResponse> list() {
    AiSecurityContext context = access.require(AiPermission.TOOL_APPROVE);
    return jdbc.query(
        select()
            + " where tenant_id=? and workspace_id=? and deleted=0"
            + " order by created_at desc limit 200",
        (rs, row) -> map(rs),
        context.getTenant().getTenantId(),
        context.getWorkspace().getWorkspaceId());
  }

  public ApprovalResponse get(String id) {
    AiSecurityContext context = access.require(AiPermission.TOOL_APPROVE);
    return require(context, id);
  }

  @Transactional
  public ApprovalResponse decide(String id, String decision, String reason) {
    String normalizedDecision = normalizeDecision(decision);
    AiSecurityContext context = access.require(AiPermission.TOOL_APPROVE);
    ApprovalResponse request = require(context, id);
    if (request.getRequestedBy().equals(context.getUserId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    if (!request.getExpiresAt().isAfter(Instant.now())) {
      throw new IllegalStateException("APPROVAL_EXPIRED");
    }
    Instant decidedAt = Instant.now();
    int updated =
        jdbc.update(
            "update ai_approval_request set status=?,updated_by=?"
                + " where id=? and tenant_id=? and workspace_id=? and status='PENDING'"
                + " and expires_at>? and deleted=0",
            normalizedDecision,
            context.getUserId(),
            id,
            context.getTenant().getTenantId(),
            context.getWorkspace().getWorkspaceId(),
            Timestamp.from(decidedAt));
    if (updated != 1) {
      throw new IllegalStateException("APPROVAL_NOT_PENDING");
    }
    jdbc.update(
        "insert into ai_approval_decision"
            + " (id,tenant_id,workspace_id,approval_request_id,decision,reason,decided_by,"
            + "decided_at,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?)",
        ids.nextId(),
        context.getTenant().getTenantId(),
        context.getWorkspace().getWorkspaceId(),
        id,
        normalizedDecision,
        reason,
        context.getUserId(),
        Timestamp.from(decidedAt),
        context.getUserId(),
        context.getUserId());
    audit.record(
        context.getTenant().getTenantId(),
        context.getWorkspace().getWorkspaceId(),
        context.getUserId(),
        request.getRunId(),
        "APPROVAL_REQUEST",
        id,
        "APPROVAL_DECIDED",
        "decision=" + normalizedDecision + ";reason=" + (reason == null ? "" : reason),
        "SUCCEEDED",
        null);
    return require(context, id);
  }

  private ApprovalResponse require(AiSecurityContext context, String id) {
    return jdbc.query(
            select() + " where tenant_id=? and workspace_id=? and id=? and deleted=0",
            (rs, row) -> map(rs),
            context.getTenant().getTenantId(),
            context.getWorkspace().getWorkspaceId(),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("APPROVAL_NOT_FOUND"));
  }

  private String normalizeDecision(String decision) {
    if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
      throw new IllegalArgumentException("APPROVAL_DECISION_INVALID");
    }
    return decision;
  }

  private String select() {
    return "select id,run_id,node_run_id,tool_call_id,tool_id,tool_version,risk_level,"
        + "input_hash,input_summary,requested_by,status,expires_at,created_at"
        + " from ai_approval_request";
  }

  private ApprovalResponse map(ResultSet rs) throws SQLException {
    return new ApprovalResponse(
        rs.getString("id"),
        rs.getString("run_id"),
        rs.getString("node_run_id"),
        rs.getString("tool_call_id"),
        rs.getString("tool_id"),
        rs.getInt("tool_version"),
        rs.getString("risk_level"),
        rs.getString("input_hash"),
        rs.getString("input_summary"),
        rs.getString("requested_by"),
        rs.getString("status"),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getTimestamp("created_at").toInstant());
  }
}
