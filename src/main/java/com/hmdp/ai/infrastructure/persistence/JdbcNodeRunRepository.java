package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.run.NodeRunRepository;
import com.hmdp.ai.domain.run.NodeRunClaim;
import com.hmdp.ai.domain.run.NodeRunStatus;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class JdbcNodeRunRepository implements NodeRunRepository {
    private final JdbcTemplate jdbcTemplate;
    private final AiIdGenerator idGenerator;

    public JdbcNodeRunRepository(JdbcTemplate jdbcTemplate, AiIdGenerator idGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = idGenerator;
    }

    @Override
    public NodeRunClaim start(ExecutionContext context, String nodeId, String nodeType, String inputJson,
                              String idempotencyKey) {
        String id = idGenerator.nextId();
        try {
            jdbcTemplate.update("insert into ai_node_run (id,tenant_id,workspace_id,run_id,node_id,node_type," +
                            "attempt,idempotency_key,status,input_json,retryable,started_at,created_by,updated_by) " +
                            "values (?,?,?,?,?,?,1,?,'RUNNING',?,0,?,?,?)",
                    id, context.getTenantId(), context.getWorkspaceId(), context.getRunId(), nodeId, nodeType,
                    idempotencyKey, inputJson, Timestamp.from(Instant.now()), context.getUserId(), context.getUserId());
            return new NodeRunClaim(id, true, null);
        } catch (DuplicateKeyException e) {
            ExistingNode existing = jdbcTemplate.queryForObject("select id,status,output_json from ai_node_run " +
                            "where tenant_id=? and workspace_id=? and idempotency_key=? and deleted=0",
                    (rs, rowNum) -> new ExistingNode(rs.getString("id"), rs.getString("status"),
                            rs.getString("output_json")), context.getTenantId(), context.getWorkspaceId(), idempotencyKey);
            if (existing != null && "SUCCEEDED".equals(existing.status)) {
                return new NodeRunClaim(existing.id, false, existing.outputJson);
            }
            if (existing == null) throw e;
            jdbcTemplate.update("update ai_node_run set status='RUNNING',attempt=attempt+1,input_json=?," +
                            "output_json=null,error_code=null,error_message=null,retryable=0,started_at=?,finished_at=null," +
                            "updated_by=? where tenant_id=? and workspace_id=? and id=? and deleted=0",
                    inputJson, Timestamp.from(Instant.now()), context.getUserId(), context.getTenantId(),
                    context.getWorkspaceId(), existing.id);
            return new NodeRunClaim(existing.id, true, null);
        }
    }

    @Override
    public void complete(String tenantId, String workspaceId, String nodeRunId, String outputJson, String usageJson) {
        jdbcTemplate.update("update ai_node_run set status='SUCCEEDED',output_json=?,usage_json=?,finished_at=?," +
                        "updated_by='runtime' where tenant_id=? and workspace_id=? and id=? and status='RUNNING' " +
                        "and deleted=0", outputJson, usageJson, Timestamp.from(Instant.now()),
                tenantId, workspaceId, nodeRunId);
    }

    @Override
    public void waitForResume(String tenantId, String workspaceId, String nodeRunId, String outputJson) {
        jdbcTemplate.update("update ai_node_run set status='WAITING',output_json=?,updated_by='runtime' where " +
                        "tenant_id=? and workspace_id=? and id=? and status='RUNNING' and deleted=0",
                outputJson, tenantId, workspaceId, nodeRunId);
    }

    @Override
    public void fail(String tenantId, String workspaceId, String nodeRunId, NodeRunStatus status,
                     String errorCode, String errorMessage, boolean retryable) {
        if (status != NodeRunStatus.FAILED && status != NodeRunStatus.TIMED_OUT
                && status != NodeRunStatus.CANCELLED) {
            throw new IllegalArgumentException("node terminal failure status is invalid");
        }
        jdbcTemplate.update("update ai_node_run set status=?,error_code=?,error_message=?,retryable=?,finished_at=?," +
                        "updated_by='runtime' where tenant_id=? and workspace_id=? and id=? and status='RUNNING' " +
                        "and deleted=0", status.name(), errorCode, errorMessage, retryable,
                Timestamp.from(Instant.now()), tenantId, workspaceId, nodeRunId);
    }

    private static final class ExistingNode {
        private final String id;
        private final String status;
        private final String outputJson;

        private ExistingNode(String id, String status, String outputJson) {
            this.id = id;
            this.status = status;
            this.outputJson = outputJson;
        }
    }
}
