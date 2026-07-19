package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.model.ModelProfileVersionRepository;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.common.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcModelProfileVersionRepository implements ModelProfileVersionRepository {
    private static final String COLUMNS = "id,tenant_id,workspace_id,model_profile_id,version,provider,model_name," +
            "base_url,secret_ref,model_type,capabilities_json,default_parameters_json,context_window," +
            "max_output_tokens,timeout_ms,retry_policy_json,fallback_model_profile_version_id,input_token_price," +
            "output_token_price,content_hash,change_note,status,published_at,published_by,created_by,updated_by," +
            "created_at,updated_at";

    private final JdbcTemplate jdbc;

    public JdbcModelProfileVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int nextVersion(String tenantId, String workspaceId, String profileId) {
        jdbc.queryForObject("select id from ai_model_profile where tenant_id=? and workspace_id=? and id=? " +
                        "and deleted=0 for update", String.class, tenantId, workspaceId, profileId);
        Integer current = jdbc.queryForObject("select coalesce(max(version),0) from ai_model_profile_version " +
                        "where tenant_id=? and workspace_id=? and model_profile_id=? and deleted=0",
                Integer.class, tenantId, workspaceId, profileId);
        return (current == null ? 0 : current) + 1;
    }

    @Override
    public ModelProfileVersion create(ModelProfileVersion version, String actorId) {
        try {
            jdbc.update("insert into ai_model_profile_version (id,tenant_id,workspace_id,model_profile_id,version," +
                            "provider,model_name,base_url,secret_ref,model_type,capabilities_json,default_parameters_json," +
                            "context_window,max_output_tokens,timeout_ms,retry_policy_json,fallback_model_profile_version_id," +
                            "input_token_price,output_token_price,content_hash,change_note,status,created_by,updated_by) " +
                            "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?)",
                    version.getId(), version.getTenantId(), version.getWorkspaceId(), version.getModelProfileId(),
                    version.getVersion(), version.getProvider(), version.getModelName(), version.getBaseUrl(),
                    version.getSecretRef(), version.getModelType().name(), version.getCapabilitiesJson(),
                    version.getDefaultParametersJson(), version.getContextWindow(), version.getMaxOutputTokens(),
                    version.getTimeoutMs(), version.getRetryPolicyJson(), version.getFallbackModelProfileVersionId(),
                    version.getInputTokenPrice(), version.getOutputTokenPrice(), version.getContentHash(),
                    version.getChangeNote(), actorId, actorId);
            return findById(version.getTenantId(), version.getWorkspaceId(), version.getId())
                    .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,
                            "model profile version was not created"));
        } catch (DuplicateKeyException e) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT,
                    "model profile version already exists");
        }
    }

    @Override
    public Optional<ModelProfileVersion> findById(String tenantId, String workspaceId, String id) {
        return query("where tenant_id=? and workspace_id=? and id=? and deleted=0", tenantId, workspaceId, id)
                .stream().findFirst();
    }

    @Override
    public Optional<ModelProfileVersion> findByProfileAndVersion(String tenantId, String workspaceId,
                                                                  String profileId, int version) {
        return query("where tenant_id=? and workspace_id=? and model_profile_id=? and version=? and deleted=0",
                tenantId, workspaceId, profileId, version).stream().findFirst();
    }

    @Override
    public Optional<ModelProfileVersion> findPublished(String tenantId, String workspaceId, String profileId) {
        return query("where tenant_id=? and workspace_id=? and model_profile_id=? and status='PUBLISHED' and deleted=0 " +
                        "order by version desc limit 1", tenantId, workspaceId, profileId).stream().findFirst();
    }

    @Override
    public List<ModelProfileVersion> findVersions(String tenantId, String workspaceId, String profileId,
                                                  int offset, int limit) {
        return query("where tenant_id=? and workspace_id=? and model_profile_id=? and deleted=0 " +
                        "order by version desc limit ? offset ?", tenantId, workspaceId, profileId, limit, offset);
    }

    @Override
    public ModelProfileVersion publish(String tenantId, String workspaceId, String profileId, int version,
                                       String actorId) {
        int updated = jdbc.update("update ai_model_profile_version set status='PUBLISHED',published_at=?," +
                        "published_by=?,updated_by=? where tenant_id=? and workspace_id=? and model_profile_id=? " +
                        "and version=? and status='DRAFT' and deleted=0", Timestamp.from(Instant.now()), actorId,
                actorId, tenantId, workspaceId, profileId, version);
        if (updated != 1) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT,
                    "only a draft model profile version can be published");
        }
        return findByProfileAndVersion(tenantId, workspaceId, profileId, version).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND, "model profile version not found"));
    }

    private List<ModelProfileVersion> query(String where, Object... args) {
        return jdbc.query("select " + COLUMNS + " from ai_model_profile_version " + where,
                (rs, row) -> map(rs), args);
    }

    private ModelProfileVersion map(ResultSet rs) throws SQLException {
        return new ModelProfileVersion(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("workspace_id"), rs.getString("model_profile_id"), rs.getInt("version"),
                rs.getString("provider"), rs.getString("model_name"), rs.getString("base_url"),
                rs.getString("secret_ref"), ModelType.valueOf(rs.getString("model_type")),
                rs.getString("capabilities_json"), rs.getString("default_parameters_json"),
                rs.getInt("context_window"), rs.getInt("max_output_tokens"), rs.getInt("timeout_ms"),
                rs.getString("retry_policy_json"), rs.getString("fallback_model_profile_version_id"),
                rs.getBigDecimal("input_token_price"), rs.getBigDecimal("output_token_price"),
                rs.getString("content_hash"), rs.getString("change_note"), rs.getString("status"),
                instant(rs.getTimestamp("published_at")), rs.getString("published_by"),
                rs.getString("created_by"), rs.getString("updated_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
