package com.hmdp.ai.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.feedback.FeedbackRecord;
import com.hmdp.ai.domain.feedback.FeedbackRepository;
import com.hmdp.ai.domain.feedback.FeedbackTag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcFeedbackRepository implements FeedbackRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public JdbcFeedbackRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    @Override public FeedbackRecord create(FeedbackRecord f){jdbc.update("insert into ai_feedback (id,tenant_id,"+
                    "workspace_id,run_id,message_id,node_run_id,rating,tags_json,comment,corrected_answer,"+
                    "review_status,status,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
            f.getId(),f.getTenantId(),f.getWorkspaceId(),f.getRunId(),f.getMessageId(),f.getNodeRunId(),f.getRating(),
            json(f.getTags()),f.getComment(),f.getCorrectedAnswer(),f.getReviewStatus(),f.getCreatedBy(),f.getCreatedBy());return f;}
    @Override public boolean nodeBelongsToRun(String tenant,String workspace,String nodeRunId,String runId){Long value=jdbc.queryForObject(
            "select count(*) from ai_node_run where tenant_id=? and workspace_id=? and id=? and run_id=? and deleted=0",
            Long.class,tenant,workspace,nodeRunId,runId);return value!=null&&value==1;}
    private String json(List<FeedbackTag> value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("feedback tags are invalid",e);}}
}
