package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.AgentKnowledgeBinding;
import com.hmdp.ai.domain.agent.AgentRepository;
import com.hmdp.ai.domain.agent.AgentToolBinding;
import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelProfileRepository;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.model.ModelProfileVersionRepository;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.run.VersionSnapshot;
import com.hmdp.common.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcAgentRepository implements AgentRepository {
    private static final String AGENT_COLUMNS = "id,tenant_id,workspace_id,code,name,description,latest_version," +
            "status,created_at,updated_at";
    private static final String VERSION_COLUMNS = "id,tenant_id,workspace_id,agent_id,version,name,description," +
            "model_profile_id,model_profile_version_id,prompt_version_id,workflow_version_id,memory_policy_json,input_schema,output_schema," +
            "execution_policy_json,response_render_policy_json,status,content_hash,change_note,published_at," +
            "published_by,created_at";

    private final JdbcTemplate jdbcTemplate;
    private final ModelProfileRepository modelProfiles;
    private final ModelProfileVersionRepository modelProfileVersions;
    private final PromptRepository prompts;
    private final RowMapper<AgentDefinition> agentMapper = this::mapAgent;
    private final RowMapper<AgentVersion> versionMapper = this::mapVersion;

    public JdbcAgentRepository(JdbcTemplate jdbcTemplate, ModelProfileRepository modelProfiles,
                               ModelProfileVersionRepository modelProfileVersions, PromptRepository prompts) {
        this.jdbcTemplate = jdbcTemplate;
        this.modelProfiles = modelProfiles;
        this.modelProfileVersions = modelProfileVersions;
        this.prompts = prompts;
    }

    @Override
    public AgentDefinition create(AgentDefinition agent, String actorId) {
        try {
            jdbcTemplate.update("insert into ai_agent (id,tenant_id,workspace_id,code,name,description," +
                            "latest_version,status,created_by,updated_by) values (?,?,?,?,?,?,0,?,?,?)",
                    agent.getId(), agent.getTenantId(), agent.getWorkspaceId(), agent.getCode(), agent.getName(),
                    agent.getDescription(), agent.getStatus(), actorId, actorId);
            return requireAgent(agent.getTenantId(), agent.getWorkspaceId(), agent.getId());
        } catch (DuplicateKeyException e) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "agent code already exists");
        }
    }

    @Override
    public Optional<AgentDefinition> findById(String tenantId, String workspaceId, String agentIdOrCode) {
        List<AgentDefinition> values = jdbcTemplate.query("select " + AGENT_COLUMNS + " from ai_agent " +
                        "where tenant_id=? and workspace_id=? and (id=? or code=?) and deleted=0",
                agentMapper, tenantId, workspaceId, agentIdOrCode, agentIdOrCode);
        return values.stream().findFirst();
    }

    @Override
    public List<AgentDefinition> findPage(String tenantId, String workspaceId, int offset, int limit) {
        return jdbcTemplate.query("select " + AGENT_COLUMNS + " from ai_agent where tenant_id=? and workspace_id=? " +
                        "and deleted=0 order by updated_at desc,id limit ? offset ?",
                agentMapper, tenantId, workspaceId, limit, offset);
    }

    @Override
    public List<AgentDefinition> findRunnablePage(String tenantId, String workspaceId, int offset, int limit) {
        return jdbcTemplate.query("select " + AGENT_COLUMNS + " from ai_agent a where a.tenant_id=? and a.workspace_id=? "
                        + "and a.deleted=0 and a.status='ACTIVE' and exists ("
                        + "select 1 from ai_agent_version v where v.tenant_id=a.tenant_id and v.workspace_id=a.workspace_id "
                        + "and v.agent_id=a.id and v.status='PUBLISHED' and v.deleted=0) "
                        + "order by a.updated_at desc,a.id limit ? offset ?",
                agentMapper, tenantId, workspaceId, limit, offset);
    }

    @Override
    public long count(String tenantId, String workspaceId) {
        Long value = jdbcTemplate.queryForObject("select count(1) from ai_agent where tenant_id=? and " +
                        "workspace_id=? and deleted=0", Long.class, tenantId, workspaceId);
        return value == null ? 0 : value;
    }

    @Override
    public long countRunnable(String tenantId, String workspaceId) {
        Long value = jdbcTemplate.queryForObject(
                "select count(1) from ai_agent a where a.tenant_id=? and a.workspace_id=? and a.deleted=0 "
                        + "and a.status='ACTIVE' and exists ("
                        + "select 1 from ai_agent_version v where v.tenant_id=a.tenant_id and v.workspace_id=a.workspace_id "
                        + "and v.agent_id=a.id and v.status='PUBLISHED' and v.deleted=0)",
                Long.class, tenantId, workspaceId);
        return value == null ? 0 : value;
    }

    @Override
    public Optional<Integer> findPublishedVersion(String tenantId, String workspaceId, String agentId) {
        List<Integer> values = jdbcTemplate.query(
                "select version from ai_agent_version where tenant_id=? and workspace_id=? and agent_id=? "
                        + "and status='PUBLISHED' and deleted=0 order by version desc limit 1",
                (rs, rowNum) -> rs.getInt("version"), tenantId, workspaceId, agentId);
        return values.stream().findFirst();
    }

    @Override
    public int lockAndNextVersion(String tenantId, String workspaceId, String agentId) {
        try {
            Integer latest = jdbcTemplate.queryForObject("select latest_version from ai_agent where tenant_id=? " +
                            "and workspace_id=? and id=? and deleted=0 for update",
                    Integer.class, tenantId, workspaceId, agentId);
            int next = (latest == null ? 0 : latest) + 1;
            jdbcTemplate.update("update ai_agent set latest_version=? where tenant_id=? and workspace_id=? and id=?",
                    next, tenantId, workspaceId, agentId);
            return next;
        } catch (EmptyResultDataAccessException e) {
            throw new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "agent not found");
        }
    }

    @Override
    public AgentVersion createVersion(AgentVersion version, List<String> toolVersionIds,
                                      List<String> knowledgeBaseVersionIds, String actorId) {
        try {
            jdbcTemplate.update("insert into ai_agent_version (id,tenant_id,workspace_id,agent_id,version,name," +
                            "description,model_profile_id,model_profile_version_id,prompt_version_id,workflow_version_id,memory_policy_json," +
                            "input_schema,output_schema,execution_policy_json,response_render_policy_json,status," +
                            "content_hash,change_note,published_at,published_by,created_by,updated_by) " +
                            "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    version.getId(), version.getTenantId(), version.getWorkspaceId(), version.getAgentId(),
                    version.getVersion(), version.getName(), version.getDescription(), version.getModelProfileId(),
                    version.getModelProfileVersionId(),
                    version.getPromptVersionId(), version.getWorkflowVersionId(), version.getMemoryPolicyJson(),
                    version.getInputSchema(), version.getOutputSchema(), version.getExecutionPolicyJson(),
                    version.getResponseRenderPolicyJson(), version.getStatus().name(), version.getContentHash(),
                    version.getChangeNote(), JdbcTime.timestamp(version.getPublishedAt()), version.getPublishedBy(),
                    actorId, actorId);
            int bindingIndex = 0;
            for (String toolVersionId : toolVersionIds) {
                jdbcTemplate.update("insert into ai_agent_tool_binding (id,tenant_id,workspace_id,agent_version_id," +
                                "tool_version_id,configuration_json,status,created_by,updated_by) values (?,?,?,?,?,'{}','ACTIVE',?,?)",
                        version.getId() + "-tool-" + (++bindingIndex), version.getTenantId(), version.getWorkspaceId(),
                        version.getId(), toolVersionId, actorId, actorId);
            }
            bindingIndex = 0;
            for (String knowledgeVersionId : knowledgeBaseVersionIds) {
                jdbcTemplate.update("insert into ai_agent_knowledge_binding (id,tenant_id,workspace_id," +
                                "agent_version_id,knowledge_base_version_id,retrieval_policy_json,status,created_by," +
                                "updated_by) values (?,?,?,?,?,'{}','ACTIVE',?,?)",
                        version.getId() + "-kb-" + (++bindingIndex), version.getTenantId(), version.getWorkspaceId(),
                        version.getId(), knowledgeVersionId, actorId, actorId);
            }
            return requireVersion(version.getTenantId(), version.getWorkspaceId(), version.getAgentId(),
                    version.getVersion());
        } catch (DuplicateKeyException e) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "agent version or binding already exists");
        }
    }

    @Override
    public Optional<AgentVersion> findVersion(String tenantId, String workspaceId, String agentId, int version) {
        Optional<AgentDefinition> agent = findById(tenantId, workspaceId, agentId);
        if (!agent.isPresent()) return Optional.empty();
        List<AgentVersion> values = jdbcTemplate.query("select " + VERSION_COLUMNS + " from ai_agent_version " +
                        "where tenant_id=? and workspace_id=? and agent_id=? and version=? and deleted=0",
                versionMapper, tenantId, workspaceId, agent.get().getId(), version);
        return values.stream().findFirst();
    }

    @Override
    public List<AgentVersion> findVersions(String tenantId, String workspaceId, String agentId, int offset, int limit) {
        AgentDefinition agent = requireAgent(tenantId, workspaceId, agentId);
        return jdbcTemplate.query("select " + VERSION_COLUMNS + " from ai_agent_version where tenant_id=? and " +
                        "workspace_id=? and agent_id=? and deleted=0 order by version desc limit ? offset ?",
                versionMapper, tenantId, workspaceId, agent.getId(), limit, offset);
    }

    @Override
    public AgentVersion publish(String tenantId, String workspaceId, String agentId, int version, String actorId) {
        AgentDefinition agent = requireAgent(tenantId, workspaceId, agentId);
        AgentVersion target = requireVersion(tenantId, workspaceId, agent.getId(), version);
        if (target.getStatus() != VersionStatus.DRAFT) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "only draft agent versions can be published");
        }
        jdbcTemplate.update("update ai_agent_version set status='ARCHIVED',updated_by=? where tenant_id=? and " +
                        "workspace_id=? and agent_id=? and status='PUBLISHED' and deleted=0",
                actorId, tenantId, workspaceId, agent.getId());
        int updated = jdbcTemplate.update("update ai_agent_version set status='PUBLISHED',published_at=?," +
                        "published_by=?,updated_by=? where tenant_id=? and workspace_id=? and agent_id=? and " +
                        "version=? and status='DRAFT' and deleted=0",
                Timestamp.from(Instant.now()), actorId, actorId, tenantId, workspaceId, agent.getId(), version);
        if (updated != 1) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "agent version changed while publishing");
        }
        return requireVersion(tenantId, workspaceId, agent.getId(), version);
    }

    @Override
    public Optional<PublishedAgentDefinition> loadPublished(String tenantId, String workspaceId,
                                                            String agentIdOrCode, int version) {
        Optional<AgentDefinition> agentOptional = findById(tenantId, workspaceId, agentIdOrCode);
        if (!agentOptional.isPresent()) return Optional.empty();
        AgentDefinition agent = agentOptional.get();
        Optional<AgentVersion> versionOptional = findVersion(tenantId, workspaceId, agent.getId(), version);
        if (!versionOptional.isPresent() || versionOptional.get().getStatus() == VersionStatus.DRAFT) {
            return Optional.empty();
        }
        AgentVersion agentVersion = versionOptional.get();
        ModelProfile model = modelProfiles.findById(tenantId, workspaceId, agentVersion.getModelProfileId())
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_PUBLISH_VALIDATION_FAILED,
                        "published agent references a missing model profile"));
        ModelProfileVersion modelVersion = modelProfileVersions.findById(tenantId, workspaceId,
                        agentVersion.getModelProfileVersionId())
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_PUBLISH_VALIDATION_FAILED,
                        "published agent references a missing model profile version"));
        PromptVersion prompt = prompts.findVersionById(tenantId, workspaceId, agentVersion.getPromptVersionId())
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_PUBLISH_VALIDATION_FAILED,
                        "published agent references a missing prompt version"));
        WorkflowRef workflow = findWorkflow(tenantId, workspaceId, agentVersion.getWorkflowVersionId());
        List<AgentToolBinding> tools = findToolBindings(tenantId, workspaceId, agentVersion.getId());
        List<AgentKnowledgeBinding> knowledge = findKnowledgeBindings(tenantId, workspaceId, agentVersion.getId());
        Map<String, Integer> toolVersions = new LinkedHashMap<>();
        tools.forEach(tool -> toolVersions.put(tool.getToolId(), tool.getToolVersion()));
        Map<String, Integer> knowledgeVersions = new LinkedHashMap<>();
        Map<String, String> indexVersions = new LinkedHashMap<>();
        knowledge.forEach(kb -> {
            knowledgeVersions.put(kb.getKnowledgeBaseId(), kb.getKnowledgeBaseVersion());
            indexVersions.put(kb.getKnowledgeBaseId(), kb.getIndexVersion());
        });
          VersionSnapshot snapshot = new VersionSnapshot(agent.getId(), agent.getCode(), agentVersion.getVersion(),
                prompt.getPromptId(), prompt.getVersion(), workflow.workflowId, workflow.version,
                model.getId(), modelVersion.getId(), modelVersion.getVersion(), modelVersion.getContentHash(),
                toolVersions, knowledgeVersions, indexVersions);
        return Optional.of(new PublishedAgentDefinition(agent, agentVersion, model, modelVersion, prompt,
                workflow.workflowId, workflow.version, workflow.status, tools, knowledge, snapshot));
    }

    @Override
    public List<AgentToolBinding> findToolBindings(String tenantId, String workspaceId, String agentVersionId) {
        return jdbcTemplate.query("select t.id as tool_id,tv.version,tv.id as tool_version_id,tv.protocol," +
                        "tv.required_permissions_json,tv.enabled from ai_agent_tool_binding b join ai_tool_version tv " +
                        "on tv.id=b.tool_version_id and tv.deleted=0 join ai_tool t on t.id=tv.tool_id and t.deleted=0 " +
                        "where b.tenant_id=? and b.workspace_id=? and b.agent_version_id=? and b.status='ACTIVE' " +
                        "and b.deleted=0 order by t.id,tv.version",
                (rs, rowNum) -> new AgentToolBinding(rs.getString("tool_id"), rs.getInt("version"),
                        rs.getString("tool_version_id"), rs.getString("protocol"),
                        rs.getString("required_permissions_json"), rs.getBoolean("enabled")),
                tenantId, workspaceId, agentVersionId);
    }

    @Override
    public List<AgentKnowledgeBinding> findKnowledgeBindings(String tenantId, String workspaceId,
                                                             String agentVersionId) {
        return jdbcTemplate.query("select kb.id as knowledge_base_id,kbv.version,kbv.id as kb_version_id," +
                        "kbv.index_version,kbv.index_status from ai_agent_knowledge_binding b " +
                        "join ai_knowledge_base_version kbv on kbv.id=b.knowledge_base_version_id and kbv.deleted=0 " +
                        "join ai_knowledge_base kb on kb.id=kbv.knowledge_base_id and kb.deleted=0 " +
                        "where b.tenant_id=? and b.workspace_id=? and b.agent_version_id=? and b.status='ACTIVE' " +
                        "and b.deleted=0 order by kb.id,kbv.version",
                (rs, rowNum) -> new AgentKnowledgeBinding(rs.getString("knowledge_base_id"),
                        rs.getInt("version"), rs.getString("kb_version_id"), rs.getString("index_version"),
                        rs.getString("index_status")), tenantId, workspaceId, agentVersionId);
    }

    private WorkflowRef findWorkflow(String tenantId, String workspaceId, String workflowVersionId) {
        List<WorkflowRef> values = jdbcTemplate.query("select w.id,wv.version,wv.status from ai_workflow_version wv " +
                        "join ai_workflow w on w.id=wv.workflow_id and w.deleted=0 where wv.tenant_id=? and " +
                        "wv.workspace_id=? and wv.id=? and wv.deleted=0",
                (rs, rowNum) -> new WorkflowRef(rs.getString("id"), rs.getInt("version"), rs.getString("status")),
                tenantId, workspaceId, workflowVersionId);
        return values.stream().findFirst().orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_PUBLISH_VALIDATION_FAILED,
                        "published agent references a missing workflow version"));
    }

    private AgentDefinition requireAgent(String tenantId, String workspaceId, String agentId) {
        return findById(tenantId, workspaceId, agentId).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "agent not found"));
    }

    private AgentVersion requireVersion(String tenantId, String workspaceId, String agentId, int version) {
        return findVersion(tenantId, workspaceId, agentId, version).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND, "agent version not found"));
    }

    private AgentDefinition mapAgent(ResultSet rs, int rowNum) throws SQLException {
        return new AgentDefinition(rs.getString("id"), rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getInt("latest_version"), rs.getString("status"),
                JdbcTime.instant(rs.getTimestamp("created_at")), JdbcTime.instant(rs.getTimestamp("updated_at")));
    }

    private AgentVersion mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new AgentVersion(rs.getString("id"), rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("agent_id"), rs.getInt("version"), rs.getString("name"),
                rs.getString("description"), rs.getString("model_profile_id"),
                rs.getString("model_profile_version_id"),
                rs.getString("prompt_version_id"), rs.getString("workflow_version_id"),
                rs.getString("memory_policy_json"), rs.getString("input_schema"), rs.getString("output_schema"),
                rs.getString("execution_policy_json"), rs.getString("response_render_policy_json"),
                VersionStatus.valueOf(rs.getString("status")), rs.getString("content_hash"),
                rs.getString("change_note"), JdbcTime.instant(rs.getTimestamp("published_at")),
                rs.getString("published_by"), JdbcTime.instant(rs.getTimestamp("created_at")));
    }

    private static final class WorkflowRef {
        private final String workflowId;
        private final int version;
        private final String status;

        private WorkflowRef(String workflowId, int version, String status) {
            this.workflowId = workflowId;
            this.version = version;
            this.status = status;
        }
    }
}
