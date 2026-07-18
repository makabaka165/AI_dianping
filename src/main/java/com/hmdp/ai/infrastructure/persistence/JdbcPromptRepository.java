package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.prompt.PromptDefinition;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
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
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcPromptRepository implements PromptRepository {
    private static final String PROMPT_COLUMNS = "id,tenant_id,workspace_id,code,name,description,latest_version," +
            "status,created_at,updated_at";
    private static final String VERSION_COLUMNS = "id,tenant_id,workspace_id,prompt_id,version,system_prompt," +
            "task_prompt,tool_instruction,retrieval_instruction,output_instruction,variables_schema,input_schema," +
            "output_schema,examples_json,status,content_hash,change_note,published_at,published_by,created_at";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<PromptDefinition> promptMapper = this::mapPrompt;
    private final RowMapper<PromptVersion> versionMapper = this::mapVersion;

    public JdbcPromptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PromptDefinition create(PromptDefinition prompt, String actorId) {
        try {
            jdbcTemplate.update("insert into ai_prompt (id,tenant_id,workspace_id,code,name,description," +
                            "latest_version,status,created_by,updated_by) values (?,?,?,?,?,?,0,?,?,?)",
                    prompt.getId(), prompt.getTenantId(), prompt.getWorkspaceId(), prompt.getCode(),
                    prompt.getName(), prompt.getDescription(), prompt.getStatus(), actorId, actorId);
            return requirePrompt(prompt.getTenantId(), prompt.getWorkspaceId(), prompt.getId());
        } catch (DuplicateKeyException e) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "prompt code already exists");
        }
    }

    @Override
    public Optional<PromptDefinition> findById(String tenantId, String workspaceId, String promptId) {
        List<PromptDefinition> values = jdbcTemplate.query("select " + PROMPT_COLUMNS + " from ai_prompt " +
                        "where tenant_id=? and workspace_id=? and (id=? or code=?) and deleted=0",
                promptMapper, tenantId, workspaceId, promptId, promptId);
        return values.stream().findFirst();
    }

    @Override
    public List<PromptDefinition> findPage(String tenantId, String workspaceId, int offset, int limit) {
        return jdbcTemplate.query("select " + PROMPT_COLUMNS + " from ai_prompt where tenant_id=? and " +
                        "workspace_id=? and deleted=0 order by updated_at desc,id limit ? offset ?",
                promptMapper, tenantId, workspaceId, limit, offset);
    }

    @Override
    public long count(String tenantId, String workspaceId) {
        Long value = jdbcTemplate.queryForObject("select count(1) from ai_prompt where tenant_id=? and " +
                        "workspace_id=? and deleted=0", Long.class, tenantId, workspaceId);
        return value == null ? 0 : value;
    }

    @Override
    public int lockAndNextVersion(String tenantId, String workspaceId, String promptId) {
        try {
            Integer latest = jdbcTemplate.queryForObject("select latest_version from ai_prompt where tenant_id=? " +
                            "and workspace_id=? and id=? and deleted=0 for update",
                    Integer.class, tenantId, workspaceId, promptId);
            int next = (latest == null ? 0 : latest) + 1;
            jdbcTemplate.update("update ai_prompt set latest_version=? where tenant_id=? and workspace_id=? and id=?",
                    next, tenantId, workspaceId, promptId);
            return next;
        } catch (EmptyResultDataAccessException e) {
            throw new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "prompt not found");
        }
    }

    @Override
    public PromptVersion createVersion(PromptVersion version, String actorId) {
        try {
            jdbcTemplate.update("insert into ai_prompt_version (id,tenant_id,workspace_id,prompt_id,version," +
                            "system_prompt,task_prompt,tool_instruction,retrieval_instruction,output_instruction," +
                            "variables_schema,input_schema,output_schema,examples_json,status,content_hash,change_note," +
                            "published_at,published_by,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    version.getId(), version.getTenantId(), version.getWorkspaceId(), version.getPromptId(),
                    version.getVersion(), version.getSystemPrompt(), version.getTaskPrompt(),
                    version.getToolInstruction(), version.getRetrievalInstruction(), version.getOutputInstruction(),
                    version.getVariablesSchema(), version.getInputSchema(), version.getOutputSchema(),
                    version.getExamplesJson(), version.getStatus().name(), version.getContentHash(),
                    version.getChangeNote(), JdbcTime.timestamp(version.getPublishedAt()), version.getPublishedBy(),
                    actorId, actorId);
            return requireVersion(version.getTenantId(), version.getWorkspaceId(), version.getPromptId(),
                    version.getVersion());
        } catch (DuplicateKeyException e) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "prompt version already exists");
        }
    }

    @Override
    public Optional<PromptVersion> findVersion(String tenantId, String workspaceId, String promptId, int version) {
        Optional<PromptDefinition> prompt = findById(tenantId, workspaceId, promptId);
        if (!prompt.isPresent()) return Optional.empty();
        List<PromptVersion> values = jdbcTemplate.query("select " + VERSION_COLUMNS + " from ai_prompt_version " +
                        "where tenant_id=? and workspace_id=? and prompt_id=? and version=? and deleted=0",
                versionMapper, tenantId, workspaceId, prompt.get().getId(), version);
        return values.stream().findFirst();
    }

    @Override
    public Optional<PromptVersion> findVersionById(String tenantId, String workspaceId, String versionId) {
        List<PromptVersion> values = jdbcTemplate.query("select " + VERSION_COLUMNS + " from ai_prompt_version " +
                        "where tenant_id=? and workspace_id=? and id=? and deleted=0",
                versionMapper, tenantId, workspaceId, versionId);
        return values.stream().findFirst();
    }

    @Override
    public List<PromptVersion> findVersions(String tenantId, String workspaceId, String promptId, int offset, int limit) {
        PromptDefinition prompt = requirePrompt(tenantId, workspaceId, promptId);
        return jdbcTemplate.query("select " + VERSION_COLUMNS + " from ai_prompt_version where tenant_id=? and " +
                        "workspace_id=? and prompt_id=? and deleted=0 order by version desc limit ? offset ?",
                versionMapper, tenantId, workspaceId, prompt.getId(), limit, offset);
    }

    @Override
    public PromptVersion publish(String tenantId, String workspaceId, String promptId, int version, String actorId) {
        PromptDefinition prompt = requirePrompt(tenantId, workspaceId, promptId);
        PromptVersion target = requireVersion(tenantId, workspaceId, prompt.getId(), version);
        if (target.getStatus() != VersionStatus.DRAFT) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "only draft prompt versions can be published");
        }
        jdbcTemplate.update("update ai_prompt_version set status='ARCHIVED',updated_by=? where tenant_id=? and " +
                        "workspace_id=? and prompt_id=? and status='PUBLISHED' and deleted=0",
                actorId, tenantId, workspaceId, prompt.getId());
        int updated = jdbcTemplate.update("update ai_prompt_version set status='PUBLISHED',published_at=?," +
                        "published_by=?,updated_by=? where tenant_id=? and workspace_id=? and prompt_id=? and " +
                        "version=? and status='DRAFT' and deleted=0",
                Timestamp.from(Instant.now()), actorId, actorId, tenantId, workspaceId, prompt.getId(), version);
        if (updated != 1) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "prompt version changed while publishing");
        }
        return requireVersion(tenantId, workspaceId, prompt.getId(), version);
    }

    private PromptDefinition requirePrompt(String tenantId, String workspaceId, String promptId) {
        return findById(tenantId, workspaceId, promptId).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "prompt not found"));
    }

    private PromptVersion requireVersion(String tenantId, String workspaceId, String promptId, int version) {
        return findVersion(tenantId, workspaceId, promptId, version).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND, "prompt version not found"));
    }

    private PromptDefinition mapPrompt(ResultSet rs, int rowNum) throws SQLException {
        return new PromptDefinition(rs.getString("id"), rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getInt("latest_version"), rs.getString("status"),
                JdbcTime.instant(rs.getTimestamp("created_at")), JdbcTime.instant(rs.getTimestamp("updated_at")));
    }

    private PromptVersion mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new PromptVersion(rs.getString("id"), rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("prompt_id"), rs.getInt("version"), rs.getString("system_prompt"),
                rs.getString("task_prompt"), rs.getString("tool_instruction"),
                rs.getString("retrieval_instruction"), rs.getString("output_instruction"),
                rs.getString("variables_schema"), rs.getString("input_schema"), rs.getString("output_schema"),
                rs.getString("examples_json"), VersionStatus.valueOf(rs.getString("status")),
                rs.getString("content_hash"), rs.getString("change_note"),
                JdbcTime.instant(rs.getTimestamp("published_at")), rs.getString("published_by"),
                JdbcTime.instant(rs.getTimestamp("created_at")));
    }
}
