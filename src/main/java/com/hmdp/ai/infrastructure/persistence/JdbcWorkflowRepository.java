package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.domain.workflow.WorkflowCatalogEntry;
import com.hmdp.ai.domain.workflow.WorkflowDefinition;
import com.hmdp.ai.domain.workflow.WorkflowEdgeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeDefinition;
import com.hmdp.ai.domain.workflow.WorkflowNodeType;
import com.hmdp.ai.domain.workflow.WorkflowRepository;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcWorkflowRepository implements WorkflowRepository {
    private static final String VERSION_COLUMNS = "id,tenant_id,workspace_id,workflow_id,version,input_schema," +
            "output_schema,variables_schema,execution_policy_json,status,content_hash,change_note,published_at,published_by";

    private final JdbcTemplate jdbc;

    public JdbcWorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WorkflowDefinition> findVersion(String tenant, String workspace, String versionId) {
        List<WorkflowDefinition> values = jdbc.query("select " + VERSION_COLUMNS + " from ai_workflow_version " +
                        "where tenant_id=? and workspace_id=? and id=? and deleted=0",
                (rs, row) -> mapVersion(rs, tenant, workspace), tenant, workspace, versionId);
        return values.stream().findFirst();
    }

    @Override
    public Optional<WorkflowCatalogEntry> findWorkflow(String tenant, String workspace, String workflowId) {
        return jdbc.query("select id,tenant_id,workspace_id,code,name,description,latest_version,status " +
                        "from ai_workflow where tenant_id=? and workspace_id=? and id=? and deleted=0",
                (rs, row) -> new WorkflowCatalogEntry(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("workspace_id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getInt("latest_version"), rs.getString("status")),
                tenant, workspace, workflowId).stream().findFirst();
    }

    @Override
    public WorkflowCatalogEntry createWorkflow(WorkflowCatalogEntry workflow, String actorId) {
        jdbc.update("insert into ai_workflow (id,tenant_id,workspace_id,code,name,description,latest_version," +
                        "status,created_by,updated_by) values (?,?,?,?,?,?,0,'ACTIVE',?,?)",
                workflow.getId(), workflow.getTenantId(), workflow.getWorkspaceId(), workflow.getCode(),
                workflow.getName(), workflow.getDescription(), actorId, actorId);
        return findWorkflow(workflow.getTenantId(), workflow.getWorkspaceId(), workflow.getId())
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,
                        "workflow was not created"));
    }

    @Override
    @Transactional
    public int lockAndNextVersion(String tenant, String workspace, String workflowId) {
        int updated = jdbc.update("update ai_workflow set latest_version=last_insert_id(latest_version+1) " +
                        "where tenant_id=? and workspace_id=? and id=? and status='ACTIVE' and deleted=0",
                tenant, workspace, workflowId);
        if (updated != 1) throw new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "workflow not found");
        Integer value = jdbc.queryForObject("select last_insert_id()", Integer.class);
        return value == null ? 0 : value;
    }

    @Override
    @Transactional
    public WorkflowDefinition createVersion(WorkflowDefinition workflow, String actorId) {
        jdbc.update("insert into ai_workflow_version (id,tenant_id,workspace_id,workflow_id,version,input_schema," +
                        "output_schema,variables_schema,execution_policy_json,status,content_hash,change_note," +
                        "created_by,updated_by) values (?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?)",
                workflow.getId(), workflow.getTenantId(), workflow.getWorkspaceId(), workflow.getWorkflowId(),
                workflow.getVersion(), workflow.getInputSchema(), workflow.getOutputSchema(),
                workflow.getVariablesSchema(), workflow.getExecutionPolicyJson(), workflow.getContentHash(),
                workflow.getChangeNote(), actorId, actorId);
        for (WorkflowNodeDefinition node : workflow.getNodes()) {
            jdbc.update("insert into ai_workflow_node (id,tenant_id,workspace_id,workflow_version_id,node_code," +
                            "node_type,name,configuration_json,input_mapping_json,output_mapping_json,timeout_ms," +
                            "max_attempts,status,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,?," +
                            "'ACTIVE',?,?)", node.getId(), workflow.getTenantId(), workflow.getWorkspaceId(),
                    workflow.getId(), node.getCode(), node.getType().name(), node.getName(),
                    node.getConfigurationJson(), node.getInputMappingJson(), node.getOutputMappingJson(),
                    node.getTimeoutMs(), node.getMaxAttempts(), actorId, actorId);
        }
        for (WorkflowEdgeDefinition edge : workflow.getEdges()) {
            jdbc.update("insert into ai_workflow_edge (id,tenant_id,workspace_id,workflow_version_id," +
                            "source_node_code,target_node_code,condition_json,priority,label,status,created_by," +
                            "updated_by) values (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)", edge.getId(),
                    workflow.getTenantId(), workflow.getWorkspaceId(), workflow.getId(), edge.getSourceNodeCode(),
                    edge.getTargetNodeCode(), edge.getConditionJson(), edge.getPriority(), edge.getLabel(),
                    actorId, actorId);
        }
        return findVersion(workflow.getTenantId(), workflow.getWorkspaceId(), workflow.getId())
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND,
                        "workflow version was not created"));
    }

    @Override
    public Optional<WorkflowDefinition> findVersionNumber(String tenant, String workspace, String workflowId,
                                                          int version) {
        return jdbc.query("select " + VERSION_COLUMNS + " from ai_workflow_version where tenant_id=? and " +
                        "workspace_id=? and workflow_id=? and version=? and deleted=0",
                (rs, row) -> mapVersion(rs, tenant, workspace), tenant, workspace, workflowId, version)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public WorkflowDefinition publish(String tenant, String workspace, String workflowId, int version,
                                      String actorId) {
        int updated = jdbc.update("update ai_workflow_version set status='PUBLISHED',published_at=?," +
                        "published_by=?,updated_by=? where tenant_id=? and workspace_id=? and workflow_id=? " +
                        "and version=? and status='DRAFT' and deleted=0", Timestamp.from(Instant.now()), actorId,
                actorId, tenant, workspace, workflowId, version);
        if (updated != 1) throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT,
                "only a draft workflow version can be published");
        return findVersionNumber(tenant, workspace, workflowId, version).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND, "workflow version not found"));
    }

    private WorkflowDefinition mapVersion(java.sql.ResultSet rs, String tenant, String workspace)
            throws java.sql.SQLException {
        String id = rs.getString("id");
        return new WorkflowDefinition(id, rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("workflow_id"), rs.getInt("version"), rs.getString("input_schema"),
                rs.getString("output_schema"), rs.getString("variables_schema"),
                rs.getString("execution_policy_json"), rs.getString("status"), nodes(tenant, workspace, id),
                edges(tenant, workspace, id), rs.getString("content_hash"), rs.getString("change_note"),
                JdbcTime.instant(rs.getTimestamp("published_at")), rs.getString("published_by"));
    }

    private List<WorkflowNodeDefinition> nodes(String tenant, String workspace, String versionId) {
        return jdbc.query("select id,node_code,node_type,name,configuration_json,input_mapping_json," +
                        "output_mapping_json,timeout_ms,max_attempts from ai_workflow_node where tenant_id=? " +
                        "and workspace_id=? and workflow_version_id=? and status='ACTIVE' and deleted=0 " +
                        "order by created_at,node_code", (rs, row) -> new WorkflowNodeDefinition(rs.getString("id"),
                        rs.getString("node_code"), WorkflowNodeType.valueOf(rs.getString("node_type")),
                        rs.getString("name"), rs.getString("configuration_json"),
                        rs.getString("input_mapping_json"), rs.getString("output_mapping_json"),
                        rs.getInt("timeout_ms"), rs.getInt("max_attempts")), tenant, workspace, versionId);
    }

    private List<WorkflowEdgeDefinition> edges(String tenant, String workspace, String versionId) {
        return jdbc.query("select id,source_node_code,target_node_code,condition_json,priority,label from " +
                        "ai_workflow_edge where tenant_id=? and workspace_id=? and workflow_version_id=? " +
                        "and status='ACTIVE' and deleted=0 order by priority desc,id",
                (rs, row) -> new WorkflowEdgeDefinition(rs.getString("id"),
                        rs.getString("source_node_code"), rs.getString("target_node_code"),
                        rs.getString("condition_json"), rs.getInt("priority"), rs.getString("label")),
                tenant, workspace, versionId);
    }
}
