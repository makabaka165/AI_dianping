package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelProfileRepository;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.common.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcModelProfileRepository implements ModelProfileRepository {
    private static final String COLUMNS = "id,tenant_id,workspace_id,code,name,provider,model_name,base_url," +
            "secret_ref,model_type,capabilities_json,default_parameters_json,context_window,max_output_tokens," +
            "timeout_ms,retry_policy_json,fallback_model_profile_id,input_token_price,output_token_price,enabled," +
            "revision,status,created_at,updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ModelProfile> rowMapper = this::map;

    public JdbcModelProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ModelProfile create(ModelProfile profile, String actorId) {
        try {
            jdbcTemplate.update("insert into ai_model_profile (id,tenant_id,workspace_id,code,name,provider," +
                            "model_name,base_url,secret_ref,model_type,capabilities_json,default_parameters_json," +
                            "context_window,max_output_tokens,timeout_ms,retry_policy_json,fallback_model_profile_id," +
                            "input_token_price,output_token_price,enabled,revision,status,created_by,updated_by) " +
                            "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    profile.getId(), profile.getTenantId(), profile.getWorkspaceId(), profile.getCode(),
                    profile.getName(), profile.getProvider(), profile.getModelName(), profile.getBaseUrl(),
                    profile.getSecretRef(), profile.getModelType().name(), profile.getCapabilitiesJson(),
                    profile.getDefaultParametersJson(), profile.getContextWindow(), profile.getMaxOutputTokens(),
                    profile.getTimeoutMs(), profile.getRetryPolicyJson(), profile.getFallbackModelProfileId(),
                    profile.getInputTokenPrice(), profile.getOutputTokenPrice(), profile.isEnabled(), 1,
                    profile.getStatus(), actorId, actorId);
            return require(profile.getTenantId(), profile.getWorkspaceId(), profile.getId());
        } catch (DuplicateKeyException e) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "model profile code already exists");
        }
    }

    @Override
    public ModelProfile update(ModelProfile profile, int expectedRevision, String actorId) {
        int updated = jdbcTemplate.update("update ai_model_profile set name=?,provider=?,model_name=?,base_url=?," +
                        "secret_ref=?,model_type=?,capabilities_json=?,default_parameters_json=?,context_window=?," +
                        "max_output_tokens=?,timeout_ms=?,retry_policy_json=?,fallback_model_profile_id=?," +
                        "input_token_price=?,output_token_price=?,enabled=?,status=?,revision=revision+1,updated_by=? " +
                        "where id=? and tenant_id=? and workspace_id=? and revision=? and deleted=0",
                profile.getName(), profile.getProvider(), profile.getModelName(), profile.getBaseUrl(),
                profile.getSecretRef(), profile.getModelType().name(), profile.getCapabilitiesJson(),
                profile.getDefaultParametersJson(), profile.getContextWindow(), profile.getMaxOutputTokens(),
                profile.getTimeoutMs(), profile.getRetryPolicyJson(), profile.getFallbackModelProfileId(),
                profile.getInputTokenPrice(), profile.getOutputTokenPrice(), profile.isEnabled(), profile.getStatus(),
                actorId, profile.getId(), profile.getTenantId(), profile.getWorkspaceId(), expectedRevision);
        if (updated != 1) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "model profile was concurrently modified");
        }
        return require(profile.getTenantId(), profile.getWorkspaceId(), profile.getId());
    }

    @Override
    public Optional<ModelProfile> findById(String tenantId, String workspaceId, String id) {
        List<ModelProfile> values = jdbcTemplate.query("select " + COLUMNS + " from ai_model_profile " +
                        "where tenant_id=? and workspace_id=? and id=? and deleted=0",
                rowMapper, tenantId, workspaceId, id);
        return values.stream().findFirst();
    }

    @Override
    public List<ModelProfile> findPage(String tenantId, String workspaceId, int offset, int limit) {
        return jdbcTemplate.query("select " + COLUMNS + " from ai_model_profile where tenant_id=? and " +
                        "workspace_id=? and deleted=0 order by updated_at desc,id limit ? offset ?",
                rowMapper, tenantId, workspaceId, limit, offset);
    }

    @Override
    public long count(String tenantId, String workspaceId) {
        Long value = jdbcTemplate.queryForObject("select count(1) from ai_model_profile where tenant_id=? " +
                        "and workspace_id=? and deleted=0", Long.class, tenantId, workspaceId);
        return value == null ? 0 : value;
    }

    private ModelProfile require(String tenantId, String workspaceId, String id) {
        return findById(tenantId, workspaceId, id).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "model profile not found"));
    }

    private ModelProfile map(ResultSet rs, int rowNum) throws SQLException {
        return new ModelProfile(rs.getString("id"), rs.getString("tenant_id"), rs.getString("workspace_id"),
                rs.getString("code"), rs.getString("name"), rs.getString("provider"),
                rs.getString("model_name"), rs.getString("base_url"), rs.getString("secret_ref"),
                ModelType.valueOf(rs.getString("model_type")), rs.getString("capabilities_json"),
                rs.getString("default_parameters_json"), rs.getInt("context_window"),
                rs.getInt("max_output_tokens"), rs.getInt("timeout_ms"), rs.getString("retry_policy_json"),
                rs.getString("fallback_model_profile_id"), rs.getBigDecimal("input_token_price"),
                rs.getBigDecimal("output_token_price"), rs.getBoolean("enabled"), rs.getInt("revision"),
                rs.getString("status"), JdbcTime.instant(rs.getTimestamp("created_at")),
                JdbcTime.instant(rs.getTimestamp("updated_at")));
    }
}
