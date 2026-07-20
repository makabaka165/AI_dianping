package com.hmdp.ai.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.memory.MemoryFact;
import com.hmdp.ai.domain.memory.MemoryRepository;
import com.hmdp.ai.domain.memory.MessageRecord;
import com.hmdp.ai.domain.run.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MemoryRecallPipeline {
    private final MemoryRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MemoryRecallPipeline(MemoryRepository repository, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public MemoryRecallResult recall(ExecutionContext context) {
        if (!repository.longTermMemoryEnabled(context.getTenantId(), context.getWorkspaceId(), context.getUserId())) {
            return new MemoryRecallResult(Collections.emptyList(), Collections.emptyList(), "",
                    Collections.emptyMap(), Collections.singletonList("LONG_TERM_MEMORY_DISABLED"),
                    Collections.singletonMap("runId", context.getRunId()));
        }
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> facts = facts(context, warnings);
        List<Map<String, Object>> episodes = episodes(context, warnings);
        Map<String, Object> profile = profile(context, warnings);
        String summary = messages(context, warnings);
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("sourceRunId", context.getRunId());
        provenance.put("conversationId", context.getConversationId());
        return new MemoryRecallResult(facts, episodes, summary, profile, warnings, provenance);
    }

    private List<Map<String, Object>> facts(ExecutionContext context, List<String> warnings) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            for (MemoryFact fact : repository.findFacts(context.getTenantId(), context.getWorkspaceId(),
                    context.getUserId(), 0, 50)) {
                if (!fact.isConfirmedByUser() && !"CONFIRMED".equals(fact.getStatus().name())) continue;
                if ("HIGH".equalsIgnoreCase(fact.getSensitivityLevel())
                        || "CRITICAL".equalsIgnoreCase(fact.getSensitivityLevel())) continue;
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("factType", fact.getFactType());
                value.put("factValue", fact.getFactValue());
                value.put("sourceMessageId", fact.getSourceMessageId());
                value.put("sourceRunId", fact.getSourceRunId());
                value.put("confidence", fact.getConfidence());
                result.add(value);
            }
        } catch (Exception e) { warnings.add("FACT_RECALL_UNAVAILABLE"); }
        return result;
    }

    private List<Map<String, Object>> episodes(ExecutionContext context, List<String> warnings) {
        try {
            return jdbc.query("select source_run_id,task_summary,result_summary,satisfaction,created_at from ai_memory_episode where tenant_id=? and workspace_id=? and user_id=? and status in ('CONFIRMED','ACTIVE') and deleted=0 order by created_at desc limit 10",
                    (rs, row) -> {
                        Map<String, Object> value = new LinkedHashMap<>();
                        value.put("sourceRunId", rs.getString("source_run_id"));
                        value.put("taskSummary", rs.getString("task_summary"));
                        value.put("resultSummary", rs.getString("result_summary"));
                        value.put("satisfaction", rs.getString("satisfaction"));
                        return value;
                    }, context.getTenantId(), context.getWorkspaceId(), context.getUserId());
        } catch (Exception e) { warnings.add("EPISODIC_RECALL_UNAVAILABLE"); return Collections.emptyList(); }
    }

    private Map<String, Object> profile(ExecutionContext context, List<String> warnings) {
        try {
            String json = jdbc.query("select profile_json from ai_user_profile where tenant_id=? and workspace_id=? and user_id=? and long_term_memory_enabled=1 and status='ACTIVE' and deleted=0",
                    rs -> rs.next() ? rs.getString(1) : null, context.getTenantId(), context.getWorkspaceId(),
                    context.getUserId());
            if (json == null) return Collections.emptyMap();
            JsonNode value = mapper.readTree(json);
            return mapper.convertValue(value, Map.class);
        } catch (Exception e) { warnings.add("PROFILE_RECALL_UNAVAILABLE"); return Collections.emptyMap(); }
    }

    private String messages(ExecutionContext context, List<String> warnings) {
        if (context.getConversationId() == null) return "";
        try {
            List<MessageRecord> messages = repository.findMessages(context.getTenantId(), context.getWorkspaceId(),
                    context.getConversationId(), 0, 12);
            StringBuilder summary = new StringBuilder();
            for (MessageRecord message : messages) {
                if (message.getContent() == null || message.getContent().trim().isEmpty()) continue;
                if (summary.length() > 5000) break;
                summary.append(message.getRole().name()).append(": ").append(message.getContent()).append('\n');
            }
            return summary.toString();
        } catch (Exception e) { warnings.add("CONVERSATION_RECALL_UNAVAILABLE"); return ""; }
    }
}
