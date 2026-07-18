package com.hmdp.ai.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.workflow.WorkflowState;
import com.hmdp.ai.domain.workflow.WorkflowStateRepository;
import com.hmdp.ai.domain.workflow.WorkflowStateStatus;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class JdbcWorkflowStateRepository implements WorkflowStateRepository {
    private static final String SELECT = "select tenant_id,workspace_id,run_id,workflow_version_id," +
            "current_node_codes_json,variables_json,completed_nodes_json,iteration_state_json," +
            "waiting_node_code,status,expires_at,state_version from ai_workflow_state";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AiIdGenerator ids;

    public JdbcWorkflowStateRepository(JdbcTemplate jdbc, ObjectMapper mapper, AiIdGenerator ids) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ids = ids;
    }

    @Override
    public Optional<WorkflowState> find(String tenantId, String workspaceId, String runId) {
        List<WorkflowState> values = jdbc.query(SELECT + " where tenant_id=? and workspace_id=? and run_id=? " +
                        "and deleted=0", this::map, tenantId, workspaceId, runId);
        return values.stream().findFirst();
    }

    @Override
    @Transactional
    public WorkflowState create(WorkflowState state, String actorId) {
        jdbc.update("insert ignore into ai_workflow_state (id,tenant_id,workspace_id,run_id,workflow_version_id," +
                        "current_node_codes_json,variables_json,completed_nodes_json,iteration_state_json,status," +
                        "state_version,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,'RUNNING',0,?,?)",
                ids.nextId(), state.getTenantId(), state.getWorkspaceId(), state.getRunId(),
                state.getWorkflowVersionId(), json(state.getCurrentNodeCodes()), json(state.getVariables()),
                json(state.getCompletedNodeKeys()), json(state.getExecutionCounts()), actorId, actorId);
        return find(state.getTenantId(), state.getWorkspaceId(), state.getRunId()).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_EXECUTION_FAILED, "workflow state was not created"));
    }

    @Override
    public WorkflowState saveProgress(WorkflowState state, String actorId) {
        int updated = jdbc.update("update ai_workflow_state set current_node_codes_json=?,variables_json=?," +
                        "completed_nodes_json=?,iteration_state_json=?,waiting_node_code=null,resume_token_hash=null," +
                        "expires_at=null,status='RUNNING',state_version=state_version+1,updated_by=? where tenant_id=? " +
                        "and workspace_id=? and run_id=? and state_version=? and deleted=0",
                json(state.getCurrentNodeCodes()), json(state.getVariables()), json(state.getCompletedNodeKeys()),
                json(state.getExecutionCounts()), actorId, state.getTenantId(), state.getWorkspaceId(),
                state.getRunId(), state.getStateVersion());
        return requireUpdated(state, updated);
    }

    @Override
    public WorkflowState saveWaiting(WorkflowState state, WorkflowStateStatus waitingStatus,
                                     String waitingNodeCode, String resumeTokenHash, Instant expiresAt,
                                     String actorId) {
        if (waitingStatus != WorkflowStateStatus.WAITING_FOR_USER
                && waitingStatus != WorkflowStateStatus.WAITING_FOR_APPROVAL) {
            throw new IllegalArgumentException("workflow waiting status is invalid");
        }
        int updated = jdbc.update("update ai_workflow_state set current_node_codes_json=?,variables_json=?," +
                        "completed_nodes_json=?,iteration_state_json=?,waiting_node_code=?,resume_token_hash=?," +
                        "expires_at=?,status=?,state_version=state_version+1,updated_by=? where tenant_id=? " +
                        "and workspace_id=? and run_id=? and state_version=? and deleted=0",
                json(state.getCurrentNodeCodes()), json(state.getVariables()), json(state.getCompletedNodeKeys()),
                json(state.getExecutionCounts()), waitingNodeCode, resumeTokenHash, Timestamp.from(expiresAt),
                waitingStatus.name(), actorId, state.getTenantId(), state.getWorkspaceId(), state.getRunId(),
                state.getStateVersion());
        return requireUpdated(state, updated);
    }

    @Override
    public boolean resume(String tenantId, String workspaceId, String runId, String resumeTokenHash,
                          Map<String, Object> resumeVariables, String actorId) {
        Optional<WorkflowState> found = find(tenantId, workspaceId, runId);
        if (!found.isPresent()) return false;
        WorkflowState current = found.get();
        Map<String, Object> variables = new LinkedHashMap<>(current.getVariables());
        variables.putAll(resumeVariables);
        return jdbc.update("update ai_workflow_state set variables_json=?,status='RUNNING',waiting_node_code=null," +
                        "resume_token_hash=null,expires_at=null,state_version=state_version+1,updated_by=? where " +
                        "tenant_id=? and workspace_id=? and run_id=? and status in " +
                        "('WAITING_FOR_USER','WAITING_FOR_APPROVAL') and resume_token_hash=? and expires_at>? " +
                        "and state_version=? and deleted=0", json(variables), actorId, tenantId, workspaceId, runId,
                resumeTokenHash, Timestamp.from(Instant.now()), current.getStateVersion()) == 1;
    }

    @Override
    public void complete(String tenantId, String workspaceId, String runId, String actorId) {
        terminal(tenantId, workspaceId, runId, WorkflowStateStatus.COMPLETED, actorId);
    }

    @Override
    public void fail(String tenantId, String workspaceId, String runId, String actorId) {
        terminal(tenantId, workspaceId, runId, WorkflowStateStatus.FAILED, actorId);
    }

    private void terminal(String tenantId, String workspaceId, String runId, WorkflowStateStatus status,
                          String actorId) {
        jdbc.update("update ai_workflow_state set status=?,current_node_codes_json='[]',state_version=state_version+1," +
                        "updated_by=? where tenant_id=? and workspace_id=? and run_id=? and deleted=0",
                status.name(), actorId, tenantId, workspaceId, runId);
    }

    private WorkflowState requireUpdated(WorkflowState state, int updated) {
        if (updated != 1) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT,
                    "workflow state was concurrently modified");
        }
        return find(state.getTenantId(), state.getWorkspaceId(), state.getRunId()).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "workflow state not found"));
    }

    private WorkflowState map(ResultSet rs, int row) throws SQLException {
        return new WorkflowState(rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("run_id"), rs.getString("workflow_version_id"),
                read(rs.getString("current_node_codes_json"), new TypeReference<List<String>>() {},
                        Collections.emptyList()),
                read(rs.getString("variables_json"), new TypeReference<Map<String, Object>>() {},
                        Collections.emptyMap()),
                read(rs.getString("completed_nodes_json"), new TypeReference<Set<String>>() {},
                        Collections.emptySet()),
                read(rs.getString("iteration_state_json"), new TypeReference<Map<String, Integer>>() {},
                        Collections.emptyMap()),
                rs.getString("waiting_node_code"), WorkflowStateStatus.valueOf(rs.getString("status")),
                JdbcTime.instant(rs.getTimestamp("expires_at")), rs.getLong("state_version"));
    }

    private <T> T read(String value, TypeReference<T> type, T fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return mapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("persisted workflow state is invalid", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("workflow state cannot be serialized", e);
        }
    }
}
