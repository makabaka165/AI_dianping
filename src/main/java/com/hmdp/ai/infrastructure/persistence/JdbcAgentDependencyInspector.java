package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.domain.agent.AgentDependencyInspector;
import com.hmdp.ai.domain.agent.DependencyStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAgentDependencyInspector implements AgentDependencyInspector {
    private final JdbcTemplate jdbcTemplate;

    public JdbcAgentDependencyInspector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<DependencyStatus> workflow(String tenantId, String workspaceId, String workflowVersionId) {
        List<DependencyStatus> values = jdbcTemplate.query("select wv.id,wv.status from ai_workflow_version wv " +
                        "where wv.tenant_id=? and wv.workspace_id=? and wv.id=? and wv.deleted=0",
                (rs, rowNum) -> new DependencyStatus(rs.getString("id"), true, rs.getString("status"),
                        true, null, null), tenantId, workspaceId, workflowVersionId);
        return values.stream().findFirst();
    }

    @Override
    public List<DependencyStatus> tools(String tenantId, String workspaceId, String agentVersionId) {
        return jdbcTemplate.query("select b.tool_version_id,tv.id as resolved_id,tv.status,tv.enabled," +
                        "tv.required_permissions_json from ai_agent_tool_binding b left join ai_tool_version tv " +
                        "on tv.id=b.tool_version_id and tv.tenant_id=b.tenant_id and tv.workspace_id=b.workspace_id " +
                        "and tv.deleted=0 where b.tenant_id=? and b.workspace_id=? and b.agent_version_id=? " +
                        "and b.status='ACTIVE' and b.deleted=0 order by b.tool_version_id",
                (rs, rowNum) -> new DependencyStatus(rs.getString("tool_version_id"),
                        rs.getString("resolved_id") != null, rs.getString("status"), rs.getBoolean("enabled"),
                        null, rs.getString("required_permissions_json")), tenantId, workspaceId, agentVersionId);
    }

    @Override
    public List<DependencyStatus> knowledgeBases(String tenantId, String workspaceId, String agentVersionId) {
        return jdbcTemplate.query("select b.knowledge_base_version_id,kbv.id as resolved_id,kbv.status," +
                        "kbv.index_status from ai_agent_knowledge_binding b left join ai_knowledge_base_version kbv " +
                        "on kbv.id=b.knowledge_base_version_id and kbv.tenant_id=b.tenant_id and " +
                        "kbv.workspace_id=b.workspace_id and kbv.deleted=0 where b.tenant_id=? and b.workspace_id=? " +
                        "and b.agent_version_id=? and b.status='ACTIVE' and b.deleted=0 " +
                        "order by b.knowledge_base_version_id",
                (rs, rowNum) -> new DependencyStatus(rs.getString("knowledge_base_version_id"),
                        rs.getString("resolved_id") != null, rs.getString("status"), true,
                        rs.getString("index_status"), null), tenantId, workspaceId, agentVersionId);
    }

    @Override
    public List<String> rawToolVersionIds(String tenantId, String workspaceId, String agentVersionId) {
        return jdbcTemplate.queryForList("select tool_version_id from ai_agent_tool_binding where tenant_id=? and " +
                        "workspace_id=? and agent_version_id=? and status='ACTIVE' and deleted=0 order by tool_version_id",
                String.class, tenantId, workspaceId, agentVersionId);
    }

    @Override
    public List<String> rawKnowledgeVersionIds(String tenantId, String workspaceId, String agentVersionId) {
        return jdbcTemplate.queryForList("select knowledge_base_version_id from ai_agent_knowledge_binding where " +
                        "tenant_id=? and workspace_id=? and agent_version_id=? and status='ACTIVE' and deleted=0 " +
                        "order by knowledge_base_version_id", String.class, tenantId, workspaceId, agentVersionId);
    }
}
