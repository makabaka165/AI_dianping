package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.RunEvent;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRunRepository implements RunRepository {
    private static final String COLUMNS = "id,tenant_id,workspace_id,user_id,session_id,conversation_id,agent_id," +
            "agent_version,status,response_mode,input_json,output_json,metadata_json,version_snapshot_json,budget_json,authorization_json," +
            "trace_id,retry_of_run_id,attempt,error_code,error_message,queued_at,started_at,finished_at,deadline_at,created_at";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AgentRunRecord> rowMapper = this::mapRun;

    public JdbcRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AgentRunRecord create(AgentRunRecord run) {
        jdbcTemplate.update("insert into ai_run (id,tenant_id,workspace_id,user_id,session_id,conversation_id," +
                        "agent_id,agent_version,status,response_mode,input_json,output_json,metadata_json," +
                        "version_snapshot_json,budget_json,authorization_json,trace_id,retry_of_run_id,attempt,error_code,error_message," +
                        "queued_at,started_at,finished_at,deadline_at,created_by,updated_by) " +
                        "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                run.getId(), run.getTenantId(), run.getWorkspaceId(), run.getUserId(), run.getSessionId(),
                run.getConversationId(), run.getAgentId(), run.getAgentVersion(), run.getStatus().name(),
                run.getResponseMode(), run.getInputJson(), run.getOutputJson(), run.getMetadataJson(),
                run.getVersionSnapshotJson(), run.getBudgetJson(), run.getAuthorizationJson(), run.getTraceId(), run.getRetryOfRunId(),
                run.getAttempt(), run.getErrorCode(), run.getErrorMessage(), JdbcTime.timestamp(run.getQueuedAt()),
                JdbcTime.timestamp(run.getStartedAt()), JdbcTime.timestamp(run.getFinishedAt()),
                JdbcTime.timestamp(run.getDeadlineAt()), run.getUserId(), run.getUserId());
        return require(run.getTenantId(), run.getWorkspaceId(), run.getId());
    }

    @Override
    public Optional<AgentRunRecord> find(String tenantId, String workspaceId, String runId) {
        List<AgentRunRecord> values = jdbcTemplate.query("select " + COLUMNS + " from ai_run where tenant_id=? " +
                        "and workspace_id=? and id=? and deleted=0", rowMapper, tenantId, workspaceId, runId);
        return values.stream().findFirst();
    }

    @Override
    public List<AgentRunRecord> findPage(String tenantId, String workspaceId, String userId, String agentId,
                                         RunStatus status, Instant createdFrom, Instant createdTo,
                                         int offset, int limit) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from ai_run where tenant_id=? and workspace_id=? "
                + "and deleted=0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(workspaceId);
        appendRunFilters(sql, args, userId, agentId, status, createdFrom, createdTo);
        sql.append(" order by created_at desc,id desc limit ? offset ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
    }

    @Override
    public long countPage(String tenantId, String workspaceId, String userId, String agentId, RunStatus status,
                          Instant createdFrom, Instant createdTo) {
        StringBuilder sql = new StringBuilder("select count(1) from ai_run where tenant_id=? and workspace_id=? "
                + "and deleted=0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(workspaceId);
        appendRunFilters(sql, args, userId, agentId, status, createdFrom, createdTo);
        Long value = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private void appendRunFilters(StringBuilder sql, List<Object> args, String userId, String agentId,
                                  RunStatus status, Instant createdFrom, Instant createdTo) {
        if (userId != null && !userId.trim().isEmpty()) {
            sql.append(" and user_id=?");
            args.add(userId);
        }
        if (agentId != null && !agentId.trim().isEmpty()) {
            sql.append(" and agent_id=?");
            args.add(agentId);
        }
        if (status != null) {
            sql.append(" and status=?");
            args.add(status.name());
        }
        if (createdFrom != null) {
            sql.append(" and created_at>=?");
            args.add(Timestamp.from(createdFrom));
        }
        if (createdTo != null) {
            sql.append(" and created_at<=?");
            args.add(Timestamp.from(createdTo));
        }
    }

    @Override
    public boolean claimQueued(String tenantId, String workspaceId, String runId) {
        return jdbcTemplate.update("update ai_run set status='RUNNING',started_at=coalesce(started_at,?)," +
                        "updated_by='runtime' where tenant_id=? and workspace_id=? and id=? and status='QUEUED' " +
                        "and deadline_at>? and deleted=0",
                Timestamp.from(Instant.now()), tenantId, workspaceId, runId, Timestamp.from(Instant.now())) == 1;
    }

    @Override
    public void complete(String tenantId, String workspaceId, String runId, String outputJson) {
        int updated = jdbcTemplate.update("update ai_run set status='COMPLETED',output_json=?,finished_at=?," +
                        "error_code=null,error_message=null,updated_by='runtime' where tenant_id=? and workspace_id=? " +
                        "and id=? and status='RUNNING' and deleted=0",
                outputJson, Timestamp.from(Instant.now()), tenantId, workspaceId, runId);
        if (updated != 1) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "run is no longer running");
        }
    }

    @Override
    public void fail(String tenantId, String workspaceId, String runId, String errorCode, String errorMessage,
                     RunStatus terminalStatus) {
        if (terminalStatus != RunStatus.FAILED && terminalStatus != RunStatus.TIMED_OUT) {
            throw new IllegalArgumentException("terminal status must be FAILED or TIMED_OUT");
        }
        jdbcTemplate.update("update ai_run set status=?,error_code=?,error_message=?,finished_at=?," +
                        "updated_by='runtime' where tenant_id=? and workspace_id=? and id=? and status not in " +
                        "('COMPLETED','CANCELLED','FAILED','TIMED_OUT') and deleted=0",
                terminalStatus.name(), errorCode, errorMessage, Timestamp.from(Instant.now()), tenantId, workspaceId, runId);
    }

    @Override
    public boolean cancel(String tenantId, String workspaceId, String runId, String actorId) {
        return jdbcTemplate.update("update ai_run set status='CANCELLED',finished_at=?,updated_by=? where tenant_id=? " +
                        "and workspace_id=? and id=? and status in ('CREATED','QUEUED','RUNNING','WAITING_FOR_USER'," +
                        "'WAITING_FOR_APPROVAL') and deleted=0",
                Timestamp.from(Instant.now()), actorId, tenantId, workspaceId, runId) == 1;
    }

    @Override
    public boolean markWaiting(String tenantId, String workspaceId, String runId, RunStatus waitingStatus,
                               String resumeTokenHash, Instant expiresAt, String actorId) {
        if (waitingStatus != RunStatus.WAITING_FOR_USER && waitingStatus != RunStatus.WAITING_FOR_APPROVAL) {
            throw new IllegalArgumentException("run waiting status is invalid");
        }
        return jdbcTemplate.update("update ai_run set status=?,resume_token_hash=?,wait_expires_at=?," +
                        "updated_by=? where tenant_id=? and workspace_id=? and id=? and status='RUNNING' and deleted=0",
                waitingStatus.name(), resumeTokenHash, Timestamp.from(expiresAt), actorId,
                tenantId, workspaceId, runId) == 1;
    }

    @Override
    public boolean resumeWaiting(String tenantId, String workspaceId, String runId, String resumeTokenHash,
                                 String resumeDataJson, String actorId) {
        return jdbcTemplate.update("update ai_run set status='QUEUED',resume_data_json=?,resume_token_hash=null," +
                        "wait_expires_at=null,queued_at=?,updated_by=? " +
                        "where tenant_id=? and workspace_id=? and id=? and status in " +
                        "('WAITING_FOR_USER','WAITING_FOR_APPROVAL') and resume_token_hash=? and wait_expires_at>? " +
                        "and deleted=0", resumeDataJson, Timestamp.from(Instant.now()), actorId, tenantId,
                workspaceId, runId, resumeTokenHash, Timestamp.from(Instant.now())) == 1;
    }

    @Override
    @Transactional
    public long appendEvent(String tenantId, String workspaceId, String runId, String eventType, String payloadJson) {
        int updated = jdbcTemplate.update("update ai_run set status_version=last_insert_id(status_version+1) " +
                        "where tenant_id=? and workspace_id=? and id=? and deleted=0",
                tenantId, workspaceId, runId);
        if (updated != 1) {
            throw new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "run not found");
        }
        Long sequence = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        long eventSequence = sequence == null ? 0 : sequence;
        jdbcTemplate.update("insert into ai_run_event (tenant_id,workspace_id,run_id,event_type,event_sequence," +
                        "payload_json) values (?,?,?,?,?,?)",
                tenantId, workspaceId, runId, eventType, eventSequence, payloadJson);
        return eventSequence;
    }

    @Override
    public List<RunEvent> findEvents(String tenantId, String workspaceId, String runId,
                                     long afterSequence, int limit) {
        return jdbcTemplate.query("select event_sequence,run_id,event_type,payload_json,created_at from ai_run_event " +
                        "where tenant_id=? and workspace_id=? and run_id=? and event_sequence>? " +
                        "order by event_sequence limit ?",
                (rs, rowNum) -> new RunEvent(rs.getLong("event_sequence"), rs.getString("run_id"),
                        rs.getString("event_type"), rs.getString("payload_json"),
                        JdbcTime.instant(rs.getTimestamp("created_at"))),
                tenantId, workspaceId, runId, afterSequence, limit);
    }

    @Override
    public long latestEventSequence(String tenantId, String workspaceId, String runId) {
        Long sequence = jdbcTemplate.queryForObject(
                "select status_version from ai_run where tenant_id=? and workspace_id=? and id=? and deleted=0",
                Long.class, tenantId, workspaceId, runId);
        return sequence == null ? 0 : sequence;
    }

    @Override
    public List<AgentRunRecord> findRecoverable(int limit) {
        return jdbcTemplate.query("select " + COLUMNS + " from ai_run where status='QUEUED' and deadline_at>? " +
                        "and deleted=0 order by queued_at,id limit ?", rowMapper, Timestamp.from(Instant.now()), limit);
    }

    @Override
    @Transactional
    public int requeueInterruptedRuns() {
        jdbcTemplate.update("update ai_run set status='TIMED_OUT',finished_at=?,error_code='RUN_DEADLINE_EXCEEDED'," +
                        "error_message='run deadline exceeded',updated_by='recovery' where status in " +
                        "('CREATED','QUEUED','RUNNING') and deadline_at<=? and deleted=0",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return jdbcTemplate.update("update ai_run set status='QUEUED',started_at=null,queued_at=?," +
                        "updated_by='recovery' where status='RUNNING' and deadline_at>? and deleted=0",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    private AgentRunRecord require(String tenantId, String workspaceId, String runId) {
        return find(tenantId, workspaceId, runId).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "run not found"));
    }

    private AgentRunRecord mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new AgentRunRecord(rs.getString("id"), rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("user_id"), rs.getString("session_id"), rs.getString("conversation_id"),
                rs.getString("agent_id"), rs.getInt("agent_version"), RunStatus.valueOf(rs.getString("status")),
                rs.getString("response_mode"), rs.getString("input_json"), rs.getString("output_json"),
                rs.getString("metadata_json"), rs.getString("version_snapshot_json"), rs.getString("budget_json"),
                rs.getString("authorization_json"), rs.getString("trace_id"), rs.getString("retry_of_run_id"), rs.getInt("attempt"),
                rs.getString("error_code"), rs.getString("error_message"),
                JdbcTime.instant(rs.getTimestamp("queued_at")), JdbcTime.instant(rs.getTimestamp("started_at")),
                JdbcTime.instant(rs.getTimestamp("finished_at")), JdbcTime.instant(rs.getTimestamp("deadline_at")),
                JdbcTime.instant(rs.getTimestamp("created_at")));
    }
}
