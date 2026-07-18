package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.domain.memory.ConversationRecord;
import com.hmdp.ai.domain.memory.MemoryFact;
import com.hmdp.ai.domain.memory.MemoryFactStatus;
import com.hmdp.ai.domain.memory.MemoryRepository;
import com.hmdp.ai.domain.memory.MessageRecord;
import com.hmdp.ai.domain.memory.MessageRole;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcMemoryRepository implements MemoryRepository {
    private final JdbcTemplate jdbc;
    private final AiIdGenerator ids;

    public JdbcMemoryRepository(JdbcTemplate jdbc, AiIdGenerator ids) { this.jdbc=jdbc;this.ids=ids; }

    @Override
    @Transactional
    public void recordCompletedRun(AgentRunRecord run,String userText,String outputJson,String answer,
                                   String citationsJson,String usageJson,String explicitFact,Instant expiresAt){
        Instant now=Instant.now();
        jdbc.update("insert into ai_conversation (id,tenant_id,workspace_id,user_id,session_id,agent_id,"+
                        "agent_version,title,status,last_message_at,created_by,updated_by) values (?,?,?,?,?,?,?,?,"+
                        "'ACTIVE',?,?,?) on duplicate key update agent_version=values(agent_version),"+
                        "last_message_at=values(last_message_at),updated_by=values(updated_by)",
                run.getConversationId(),run.getTenantId(),run.getWorkspaceId(),run.getUserId(),run.getSessionId(),
                run.getAgentId(),run.getAgentVersion(),title(userText),Timestamp.from(now),run.getUserId(),run.getUserId());
        String userMessageId=ids.nextId();
        jdbc.update("insert ignore into ai_message (id,tenant_id,workspace_id,conversation_id,run_id,agent_id,"+
                        "agent_version,role,content,structured_content_json,attachments_json,citations_json,"+
                        "token_usage_json,created_by,updated_by) values (?,?,?,?,?,?,?,'USER',?,?,'[]','[]','{}',?,?)",
                userMessageId,run.getTenantId(),run.getWorkspaceId(),run.getConversationId(),run.getId(),
                run.getAgentId(),run.getAgentVersion(),limit(userText,8000),run.getInputJson(),run.getUserId(),run.getUserId());
        jdbc.update("insert ignore into ai_message (id,tenant_id,workspace_id,conversation_id,run_id,agent_id,"+
                        "agent_version,role,content,structured_content_json,attachments_json,citations_json,"+
                        "token_usage_json,created_by,updated_by) values (?,?,?,?,?,?,?,'ASSISTANT',?,?,'[]',?,?,?,?)",
                ids.nextId(),run.getTenantId(),run.getWorkspaceId(),run.getConversationId(),run.getId(),
                run.getAgentId(),run.getAgentVersion(),limit(answer,16000),outputJson,jsonOrEmpty(citationsJson,"[]"),
                jsonOrEmpty(usageJson,"{}"),run.getUserId(),run.getUserId());
        jdbc.update("insert ignore into ai_memory_episode (id,tenant_id,workspace_id,user_id,conversation_id,"+
                        "source_run_id,agent_id,agent_version,task_summary,result_summary,tool_failures_json,"+
                        "satisfaction,status,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,'[]','UNKNOWN',"+
                        "'ACTIVE',?,?)",ids.nextId(),run.getTenantId(),run.getWorkspaceId(),run.getUserId(),
                run.getConversationId(),run.getId(),run.getAgentId(),run.getAgentVersion(),limit(userText,2000),
                limit(answer,2000),run.getUserId(),run.getUserId());
        if(explicitFact!=null&&longTermMemoryEnabled(run.getTenantId(),run.getWorkspaceId(),run.getUserId())){
            String persistedUserMessage=jdbc.query("select id from ai_message where tenant_id=? and workspace_id=? "+
                            "and run_id=? and role='USER' and deleted=0",(rs,row)->rs.getString(1),run.getTenantId(),
                    run.getWorkspaceId(),run.getId()).stream().findFirst().orElse(userMessageId);
            jdbc.update("insert into ai_memory_fact (id,tenant_id,workspace_id,user_id,fact_type,fact_value,"+
                            "source_message_id,source_run_id,confidence,confirmed_by_user,sensitivity_level,"+
                            "expires_at,status,created_by,updated_by) values (?,?,?,?,?,?,?,?,1,1,'NORMAL',?,'CONFIRMED',?,?)",
                    ids.nextId(),run.getTenantId(),run.getWorkspaceId(),run.getUserId(),"USER_DECLARED",
                    limit(explicitFact,2000),persistedUserMessage,run.getId(),JdbcTime.timestamp(expiresAt),
                    run.getUserId(),run.getUserId());
        }
    }

    @Override public Optional<ConversationRecord> findConversation(String tenant,String workspace,String user,String id){
        return jdbc.query("select id,tenant_id,workspace_id,user_id,session_id,agent_id,agent_version,title,status,"+
                        "last_message_at,created_at from ai_conversation where tenant_id=? and workspace_id=? and "+
                        "user_id=? and id=? and deleted=0",(rs,row)->conversation(rs),tenant,workspace,user,id).stream().findFirst();}
    @Override public List<MessageRecord> findMessages(String tenant,String workspace,String conversation,int offset,int limit){
        return jdbc.query("select id,tenant_id,workspace_id,conversation_id,run_id,agent_id,agent_version,role,"+
                        "content,structured_content_json,tool_call_id,attachments_json,citations_json,token_usage_json,"+
                        "created_at from ai_message where tenant_id=? and workspace_id=? and conversation_id=? and "+
                        "deleted=0 order by created_at,id limit ? offset ?",(rs,row)->message(rs),tenant,workspace,
                conversation,limit,offset);}
    @Override public long countMessages(String tenant,String workspace,String conversation){Long v=jdbc.queryForObject(
            "select count(*) from ai_message where tenant_id=? and workspace_id=? and conversation_id=? and deleted=0",
            Long.class,tenant,workspace,conversation);return v==null?0:v;}
    @Override public boolean messageBelongsToRun(String tenant,String workspace,String messageId,String runId){Long v=jdbc.queryForObject(
            "select count(*) from ai_message where tenant_id=? and workspace_id=? and id=? and run_id=? and deleted=0",
            Long.class,tenant,workspace,messageId,runId);return v!=null&&v==1;}
    @Override public List<MemoryFact> findFacts(String tenant,String workspace,String user,int offset,int limit){return jdbc.query(
            "select id,tenant_id,workspace_id,user_id,fact_type,fact_value,source_message_id,source_run_id,confidence,"+
                    "confirmed_by_user,sensitivity_level,expires_at,status,created_at,updated_at from ai_memory_fact "+
                    "where tenant_id=? and workspace_id=? and user_id=? and deleted=0 and status<>'DELETED' and "+
                    "(expires_at is null or expires_at>?) order by updated_at desc,id limit ? offset ?",
            (rs,row)->fact(rs),tenant,workspace,user,Timestamp.from(Instant.now()),limit,offset);}
    @Override public long countFacts(String tenant,String workspace,String user){Long v=jdbc.queryForObject(
            "select count(*) from ai_memory_fact where tenant_id=? and workspace_id=? and user_id=? and deleted=0 "+
                    "and status<>'DELETED' and (expires_at is null or expires_at>?)",Long.class,tenant,workspace,user,
            Timestamp.from(Instant.now()));return v==null?0:v;}
    @Override @Transactional public Optional<MemoryFact> confirmFact(String tenant,String workspace,String user,String id,String actor){
        jdbc.update("update ai_memory_fact set confirmed_by_user=1,status='CONFIRMED',updated_by=? where tenant_id=? "+
                "and workspace_id=? and user_id=? and id=? and status='CANDIDATE' and deleted=0",actor,tenant,workspace,user,id);
        return findFact(tenant,workspace,user,id);}
    @Override @Transactional public Optional<MemoryFact> correctFact(String tenant,String workspace,String user,String id,String value,String actor){
        jdbc.update("update ai_memory_fact set fact_value=?,confirmed_by_user=1,status='CORRECTED',confidence=1,"+
                        "updated_by=? where tenant_id=? and workspace_id=? and user_id=? and id=? and deleted=0",
                limit(value,2000),actor,tenant,workspace,user,id);return findFact(tenant,workspace,user,id);}
    @Override public boolean deleteFact(String tenant,String workspace,String user,String id,String actor){return jdbc.update(
            "update ai_memory_fact set deleted=1,status='DELETED',updated_by=? where tenant_id=? and workspace_id=? "+
                    "and user_id=? and id=? and deleted=0",actor,tenant,workspace,user,id)==1;}
    @Override public int deleteAllFacts(String tenant,String workspace,String user,String actor){return jdbc.update(
            "update ai_memory_fact set deleted=1,status='DELETED',updated_by=? where tenant_id=? and workspace_id=? "+
                    "and user_id=? and deleted=0",actor,tenant,workspace,user);}
    @Override public boolean longTermMemoryEnabled(String tenant,String workspace,String user){List<Boolean> values=jdbc.query(
            "select long_term_memory_enabled from ai_user_profile where tenant_id=? and workspace_id=? and user_id=? "+
                    "and deleted=0",(rs,row)->rs.getBoolean(1),tenant,workspace,user);return values.isEmpty()||values.get(0);}
    @Override public void setLongTermMemoryEnabled(String tenant,String workspace,String user,boolean enabled,String actor){jdbc.update(
            "insert into ai_user_profile (id,tenant_id,workspace_id,user_id,profile_json,long_term_memory_enabled,status,"+
                    "created_by,updated_by) values (?,?,?,?,'{}',?,'ACTIVE',?,?) on duplicate key update "+
                    "long_term_memory_enabled=values(long_term_memory_enabled),updated_by=values(updated_by),deleted=0",
            ids.nextId(),tenant,workspace,user,enabled,actor,actor);}
    @Override public void saveWorkingSnapshot(String tenant,String workspace,String runId,String conversation,String json,
                                              Instant expiresAt,String actor){jdbc.update(
            "insert into ai_working_memory_snapshot (id,tenant_id,workspace_id,run_id,conversation_id,snapshot_json,"+
                    "expires_at,status,created_by,updated_by) values (?,?,?,?,?,?,?,'ACTIVE',?,?) on duplicate key update "+
                    "snapshot_json=values(snapshot_json),expires_at=values(expires_at),updated_by=values(updated_by)",
            ids.nextId(),tenant,workspace,runId,conversation,json,Timestamp.from(expiresAt),actor,actor);}

    private Optional<MemoryFact> findFact(String tenant,String workspace,String user,String id){return jdbc.query(
            "select id,tenant_id,workspace_id,user_id,fact_type,fact_value,source_message_id,source_run_id,confidence,"+
                    "confirmed_by_user,sensitivity_level,expires_at,status,created_at,updated_at from ai_memory_fact "+
                    "where tenant_id=? and workspace_id=? and user_id=? and id=? and deleted=0",(rs,row)->fact(rs),
            tenant,workspace,user,id).stream().findFirst();}
    private ConversationRecord conversation(ResultSet rs)throws SQLException{return new ConversationRecord(rs.getString("id"),
            rs.getString("tenant_id"),rs.getString("workspace_id"),rs.getString("user_id"),rs.getString("session_id"),
            rs.getString("agent_id"),rs.getInt("agent_version"),rs.getString("title"),rs.getString("status"),
            JdbcTime.instant(rs.getTimestamp("last_message_at")),JdbcTime.instant(rs.getTimestamp("created_at")));}
    private MessageRecord message(ResultSet rs)throws SQLException{return new MessageRecord(rs.getString("id"),
            rs.getString("tenant_id"),rs.getString("workspace_id"),rs.getString("conversation_id"),rs.getString("run_id"),
            rs.getString("agent_id"),rs.getInt("agent_version"),MessageRole.valueOf(rs.getString("role")),
            rs.getString("content"),rs.getString("structured_content_json"),rs.getString("tool_call_id"),
            rs.getString("attachments_json"),rs.getString("citations_json"),rs.getString("token_usage_json"),
            JdbcTime.instant(rs.getTimestamp("created_at")));}
    private MemoryFact fact(ResultSet rs)throws SQLException{return new MemoryFact(rs.getString("id"),rs.getString("tenant_id"),
            rs.getString("workspace_id"),rs.getString("user_id"),rs.getString("fact_type"),rs.getString("fact_value"),
            rs.getString("source_message_id"),rs.getString("source_run_id"),rs.getDouble("confidence"),
            rs.getBoolean("confirmed_by_user"),rs.getString("sensitivity_level"),JdbcTime.instant(rs.getTimestamp("expires_at")),
            MemoryFactStatus.valueOf(rs.getString("status")),JdbcTime.instant(rs.getTimestamp("created_at")),
            JdbcTime.instant(rs.getTimestamp("updated_at")));}
    private String title(String value){String v=limit(value,80);return v==null||v.trim().isEmpty()?"Agent conversation":v;}
    private String jsonOrEmpty(String value,String fallback){return value==null||value.trim().isEmpty()?fallback:value;}
    private String limit(String value,int max){return value==null?null:value.length()<=max?value:value.substring(0,max);}
}
