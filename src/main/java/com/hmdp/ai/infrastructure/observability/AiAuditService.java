package com.hmdp.ai.infrastructure.observability;
import com.hmdp.ai.domain.observability.AiAuditPort;import com.hmdp.ai.infra.AiLogSanitizer;import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;
@Service public class AiAuditService implements AiAuditPort {private final JdbcTemplate jdbc;private final AiIdGenerator ids;
    public AiAuditService(JdbcTemplate jdbc,AiIdGenerator ids){this.jdbc=jdbc;this.ids=ids;}
    @Override public void record(String tenant,String workspace,String user,String run,String type,String resource,String action,String summary,String status,String error){
        jdbc.update("insert into ai_audit_event (id,tenant_id,workspace_id,user_id,run_id,resource_type,resource_id,"+
                        "action,summary,status,error_code,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                ids.nextId(),tenant,workspace,user,run,type,resource,action,AiLogSanitizer.safe(summary,1000),status,error,user,user);}}
