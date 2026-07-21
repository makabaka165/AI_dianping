package com.hmdp.ai.infrastructure.persistence;

import com.hmdp.ai.domain.evaluation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;import java.sql.SQLException;import java.sql.Timestamp;import java.time.Instant;import java.util.*;

@Repository
public class JdbcEvaluationRepository implements EvaluationRepository {
    private final JdbcTemplate jdbc; public JdbcEvaluationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public EvaluationDataset createDataset(EvaluationDataset d,String actor){jdbc.update("insert into ai_eval_dataset "+
            "(id,tenant_id,workspace_id,code,name,description,evaluation_type,status,created_by,updated_by) "+
            "values (?,?,?,?,?,?,?,'ACTIVE',?,?)",d.getId(),d.getTenantId(),d.getWorkspaceId(),d.getCode(),d.getName(),
            d.getDescription(),d.getType().name(),actor,actor);return d;}
    @Override public Optional<EvaluationDataset> findDataset(String t,String w,String id){return jdbc.query(
            "select id,tenant_id,workspace_id,code,name,description,evaluation_type,status from ai_eval_dataset "+
                    "where tenant_id=? and workspace_id=? and id=? and deleted=0",(rs,row)->dataset(rs),t,w,id).stream().findFirst();}
    @Override public EvaluationCase createCase(EvaluationCase c,String actor){jdbc.update("insert into ai_eval_case "+
            "(id,tenant_id,workspace_id,dataset_id,name,input_json,expected_json,assertions_json,status,created_by,updated_by) "+
            "values (?,?,?,?,?,?,?,?,'ACTIVE',?,?)",c.getId(),c.getTenantId(),c.getWorkspaceId(),c.getDatasetId(),
            c.getName(),c.getInputJson(),c.getExpectedJson(),c.getAssertionsJson(),actor,actor);return c;}
    @Override public List<EvaluationCase> findCases(String t,String w,String dataset){return jdbc.query(
            "select id,tenant_id,workspace_id,dataset_id,name,input_json,expected_json,assertions_json,status "+
                    "from ai_eval_case where tenant_id=? and workspace_id=? and dataset_id=? and status='ACTIVE' "+
                    "and deleted=0 order by created_at,id",(rs,row)->evalCase(rs),t,w,dataset);}
    @Override public EvaluationRun createRun(EvaluationRun r,String actor){jdbc.update("insert into ai_eval_run "+
            "(id,tenant_id,workspace_id,dataset_id,target_type,target_id,target_version,status,summary_json,started_at,"+
                    "created_by,updated_by) values (?,?,?,?,?,?,?,'RUNNING','{}',?,?,?)",r.getId(),r.getTenantId(),
            r.getWorkspaceId(),r.getDatasetId(),r.getTargetType(),r.getTargetId(),r.getTargetVersion(),
            Timestamp.from(Instant.now()),actor,actor);return r;}
    @Override @Transactional public void saveResults(String runId,List<EvaluationResult> results,String summary,String actor){
        for(EvaluationResult r:results)jdbc.update("insert into ai_eval_result (id,tenant_id,workspace_id,eval_run_id,"+
                        "eval_case_id,execution_run_id,actual_json,metrics_json,passed,error_code,error_message,status,"+
                        "created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",r.getId(),
                r.getTenantId(),r.getWorkspaceId(),r.getEvalRunId(),r.getEvalCaseId(),r.getExecutionRunId(),
                r.getActualJson(),r.getMetricsJson(),r.isPassed(),r.getErrorCode(),r.getErrorMessage(),r.getStatus(),
                actor,actor);
        jdbc.update("update ai_eval_run set status='COMPLETED',summary_json=?,finished_at=?,updated_by=? where id=? "+
                "and status='RUNNING' and deleted=0",summary,Timestamp.from(Instant.now()),actor,runId);}
    @Override public Optional<EvaluationRun> findRun(String t,String w,String id){return jdbc.query("select id,tenant_id,"+
            "workspace_id,dataset_id,target_type,target_id,target_version,status,summary_json,started_at,finished_at "+
            "from ai_eval_run where tenant_id=? and workspace_id=? and id=? and deleted=0",(rs,row)->run(rs),t,w,id).stream().findFirst();}
    @Override public List<EvaluationResult> findResults(String t,String w,String run){return jdbc.query("select id,tenant_id,"+
            "workspace_id,eval_run_id,eval_case_id,execution_run_id,actual_json,metrics_json,passed,error_code,error_message,status "+
            "from ai_eval_result where tenant_id=? and workspace_id=? and eval_run_id=? and deleted=0 order by created_at,id",
            (rs,row)->result(rs),t,w,run);}
    private EvaluationDataset dataset(ResultSet rs)throws SQLException{return new EvaluationDataset(rs.getString("id"),rs.getString("tenant_id"),rs.getString("workspace_id"),rs.getString("code"),rs.getString("name"),rs.getString("description"),EvaluationType.valueOf(rs.getString("evaluation_type")),rs.getString("status"));}
    private EvaluationCase evalCase(ResultSet rs)throws SQLException{return new EvaluationCase(rs.getString("id"),rs.getString("tenant_id"),rs.getString("workspace_id"),rs.getString("dataset_id"),rs.getString("name"),rs.getString("input_json"),rs.getString("expected_json"),rs.getString("assertions_json"),rs.getString("status"));}
    private EvaluationRun run(ResultSet rs)throws SQLException{return new EvaluationRun(rs.getString("id"),rs.getString("tenant_id"),rs.getString("workspace_id"),rs.getString("dataset_id"),rs.getString("target_type"),rs.getString("target_id"),(Integer)rs.getObject("target_version"),rs.getString("status"),rs.getString("summary_json"),JdbcTime.instant(rs.getTimestamp("started_at")),JdbcTime.instant(rs.getTimestamp("finished_at")));}
    private EvaluationResult result(ResultSet rs)throws SQLException{return new EvaluationResult(rs.getString("id"),rs.getString("tenant_id"),rs.getString("workspace_id"),rs.getString("eval_run_id"),rs.getString("eval_case_id"),rs.getString("execution_run_id"),rs.getString("actual_json"),rs.getString("metrics_json"),rs.getBoolean("passed"),rs.getString("error_code"),rs.getString("error_message"),rs.getString("status"));}
}
