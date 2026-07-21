package com.hmdp.ai.infrastructure.observability;

import com.hmdp.ai.domain.observability.RunInspectionItem;
import com.hmdp.ai.domain.observability.RunInspectionPort;
import com.hmdp.ai.domain.observability.RunUsageSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcRunInspectionAdapter implements RunInspectionPort {
    private final JdbcTemplate jdbc;

    public JdbcRunInspectionAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RunInspectionItem> nodeRuns(String tenantId, String workspaceId, String runId) {
        return jdbc.query("select id,node_id,node_type,status,error_code," +
                        "coalesce(error_message,output_json) summary," +
                        "coalesce(timestampdiff(microsecond,started_at,finished_at)/1000,0) latency_ms,created_at " +
                        "from ai_node_run where tenant_id=? and workspace_id=? and run_id=? and deleted=0 " +
                        "order by created_at,id",
                (result, row) -> item(result, "NODE", result.getString("node_id"),
                        result.getString("node_type"), 0, 0), tenantId, workspaceId, runId);
    }

    @Override
    public List<RunInspectionItem> modelCalls(String tenantId, String workspaceId, String runId) {
        return jdbc.query("select id,node_run_id,concat(provider,':',model_name) name,status,error_code," +
                        "coalesce(response_summary,request_summary) summary,latency_ms,input_tokens,output_tokens," +
                        "created_at from ai_model_call where tenant_id=? and workspace_id=? and run_id=? " +
                        "and deleted=0 order by created_at,id",
                (result, row) -> item(result, "MODEL", result.getString("node_run_id"),
                        result.getString("name"), result.getLong("input_tokens"),
                        result.getLong("output_tokens")), tenantId, workspaceId, runId);
    }

    @Override
    public List<RunInspectionItem> toolCalls(String tenantId, String workspaceId, String runId) {
        return jdbc.query("select id,node_run_id,concat(protocol,':',tool_id,':',tool_version) name,status," +
                        "error_code,coalesce(result_summary,invocation_summary) summary,latency_ms,created_at " +
                        "from ai_tool_call where tenant_id=? and workspace_id=? and run_id=? and deleted=0 " +
                        "order by created_at,id",
                (result, row) -> item(result, "TOOL", result.getString("node_run_id"),
                        result.getString("name"), 0, 0), tenantId, workspaceId, runId);
    }

    @Override
    public List<RunInspectionItem> retrievals(String tenantId, String workspaceId, String runId) {
        return jdbc.query("select id,node_run_id,concat(knowledge_base_id,':',knowledge_base_version,':'," +
                        "index_version) name,status,error_code,query_summary summary,latency_ms,created_at " +
                        "from ai_retrieval_record where tenant_id=? and workspace_id=? and run_id=? and deleted=0 " +
                        "order by created_at,id",
                (result, row) -> item(result, "RETRIEVAL", result.getString("node_run_id"),
                        result.getString("name"), 0, 0), tenantId, workspaceId, runId);
    }

    @Override
    public List<RunInspectionItem> artifacts(String tenantId, String workspaceId, String runId) {
        return jdbc.query("select id,run_id reference_id,name,status,null error_code," +
                        "concat(content_type,':',size_bytes) summary,0 latency_ms,created_at from ai_artifact " +
                        "where tenant_id=? and workspace_id=? and run_id=? and deleted=0 order by created_at,id",
                (result, row) -> item(result, "ARTIFACT", result.getString("reference_id"),
                        result.getString("name"), 0, 0), tenantId, workspaceId, runId);
    }

    @Override
    public RunUsageSummary usage(String tenantId, String workspaceId, String runId) {
        Map<String, Object> row = jdbc.queryForMap(
                "select coalesce(sum(input_tokens),0) input_tokens," +
                        "coalesce(sum(output_tokens),0) output_tokens,count(*) model_calls," +
                        "coalesce((select count(*) from ai_tool_call where tenant_id=? and workspace_id=? " +
                        "and run_id=? and deleted=0),0) tool_calls," +
                        "coalesce(sum(estimated_cost),0) total_cost from ai_model_call " +
                        "where tenant_id=? and workspace_id=? and run_id=? and deleted=0",
                tenantId, workspaceId, runId, tenantId, workspaceId, runId);
        return new RunUsageSummary(number(row.get("input_tokens")), number(row.get("output_tokens")),
                number(row.get("model_calls")), number(row.get("tool_calls")),
                new BigDecimal(String.valueOf(row.get("total_cost"))));
    }

    private RunInspectionItem item(ResultSet result, String category, String reference, String name,
                                   long inputTokens, long outputTokens) throws SQLException {
        Timestamp timestamp = result.getTimestamp("created_at");
        return new RunInspectionItem(result.getString("id"), category, reference, name,
                result.getString("status"), result.getString("error_code"), result.getString("summary"),
                result.getLong("latency_ms"), inputTokens, outputTokens,
                timestamp == null ? null : timestamp.toInstant());
    }

    private long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
