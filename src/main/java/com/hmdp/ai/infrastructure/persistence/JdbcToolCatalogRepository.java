package com.hmdp.ai.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.tool.ToolCatalogEntry;
import com.hmdp.ai.domain.tool.ToolCatalogRepository;
import com.hmdp.ai.domain.tool.ToolVersionDraft;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class JdbcToolCatalogRepository implements ToolCatalogRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public JdbcToolCatalogRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    @Override @Transactional public ToolCatalogEntry create(ToolCatalogEntry tool,ToolVersionDraft version,String actor){jdbc.update("insert into ai_tool (id,tenant_id,workspace_id,code,name,description,latest_version,status,created_by,updated_by) values (?,?,?,?,?,?,1,'ACTIVE',?,?)",tool.getId(),tool.getTenantId(),tool.getWorkspaceId(),tool.getCode(),tool.getName(),tool.getDescription(),actor,actor);jdbc.update("insert into ai_tool_version (id,tenant_id,workspace_id,tool_id,version,name,description,protocol,input_schema,output_schema,risk_level,side_effect,idempotent,timeout_ms,retry_policy_json,required_permissions_json,configuration_json,enabled,status,content_hash,change_note,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?)",version.getId(),version.getTenantId(),version.getWorkspaceId(),version.getToolId(),version.getVersion(),version.getName(),version.getDescription(),version.getProtocol().name(),version.getInputSchema(),version.getOutputSchema(),version.getRiskLevel().name(),version.isSideEffect(),version.isIdempotent(),version.getTimeoutMs(),version.getRetryPolicyJson(),json(version.getRequiredPermissions()),version.getConfigurationJson(),version.isEnabled(),version.getContentHash(),version.getChangeNote(),actor,actor);return tool;}
    @Override public List<ToolCatalogEntry> findPage(String tenant,String workspace,int offset,int limit){return jdbc.query("select id,tenant_id,workspace_id,code,name,description,latest_version,status from ai_tool where tenant_id=? and workspace_id=? and deleted=0 order by updated_at desc,id limit ? offset ?",(rs,row)->new ToolCatalogEntry(rs.getString("id"),rs.getString("tenant_id"),rs.getString("workspace_id"),rs.getString("code"),rs.getString("name"),rs.getString("description"),rs.getInt("latest_version"),rs.getString("status")),tenant,workspace,limit,offset);}
    @Override public long count(String tenant,String workspace){Long value=jdbc.queryForObject("select count(*) from ai_tool where tenant_id=? and workspace_id=? and deleted=0",Long.class,tenant,workspace);return value==null?0:value;}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("tool permissions cannot be serialized",e);}}
}
