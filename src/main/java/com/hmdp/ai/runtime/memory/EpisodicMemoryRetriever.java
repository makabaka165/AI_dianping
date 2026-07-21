package com.hmdp.ai.runtime.memory;

import com.hmdp.ai.domain.run.ExecutionContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EpisodicMemoryRetriever {
  private final JdbcTemplate jdbc;

  public EpisodicMemoryRetriever(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> retrieve(ExecutionContext context) {
    return jdbc.query(
        "select source_run_id,task_summary,result_summary,satisfaction,created_at "
            + "from ai_memory_episode where tenant_id=? and workspace_id=? and user_id=? "
            + "and status in ('CONFIRMED','ACTIVE') and deleted=0 "
            + "order by created_at desc limit 50",
        (rs, row) -> {
          Map<String, Object> value = new LinkedHashMap<>();
          value.put("sourceRunId", rs.getString("source_run_id"));
          value.put("taskSummary", rs.getString("task_summary"));
          value.put("resultSummary", rs.getString("result_summary"));
          value.put("satisfaction", rs.getString("satisfaction"));
          return value;
        },
        context.getTenantId(),
        context.getWorkspaceId(),
        context.getUserId());
  }
}
