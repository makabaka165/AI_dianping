package com.hmdp.ai.runtime.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.run.ExecutionContext;
import java.util.Collections;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserProfileRetriever {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public UserProfileRetriever(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Map<String, Object> retrieve(ExecutionContext context) {
    String json =
        jdbc.query(
            "select profile_json from ai_user_profile where tenant_id=? "
                + "and workspace_id=? and user_id=? and long_term_memory_enabled=1 "
                + "and status='ACTIVE' and deleted=0",
            rs -> rs.next() ? rs.getString(1) : null,
            context.getTenantId(),
            context.getWorkspaceId(),
            context.getUserId());
    if (json == null || json.trim().isEmpty()) return Collections.emptyMap();
    try {
      return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception error) {
      throw new IllegalStateException("MEMORY_PROFILE_INVALID", error);
    }
  }
}
