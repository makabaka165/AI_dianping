package com.hmdp.ai.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hmdp.ai.domain.observability.InvocationContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.tool.ToolAuditDetails;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolProtocol;
import com.hmdp.ai.domain.tool.ToolResult;
import com.hmdp.ai.domain.tool.ToolRiskLevel;
import com.hmdp.ai.guard.PiiRedactionService;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.tool.ToolCallStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.hmdp.ai.domain.security.AiPermission;

class JdbcToolAuditAdapterTest {
    @Test
    void persistsExecutionTelemetryAndNodeRunIdentity() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        PiiRedactionService redactor = mock(PiiRedactionService.class);
        when(redactor.redact(org.mockito.ArgumentMatchers.anyString())).thenReturn("masked");
        JdbcToolAuditAdapter adapter = new JdbcToolAuditAdapter(jdbc, new ObjectMapper(), redactor);
        InvocationContext context = new InvocationContext("tenant", "workspace", "run-1", "node-1",
                "invocation-1", "trace-1", "agent-1", 1, "user-1");
        ExecutionBudget budget = ExecutionBudget.defaults();
        com.hmdp.ai.domain.run.ExecutionContext execution = new com.hmdp.ai.domain.run.ExecutionContext(
                "tenant", "workspace", "user-1", "session", null, "run-1", "agent-1", 1,
                "en-US", "UTC", Collections.emptyList(), Collections.emptyList(),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), budget,
                Instant.now().plusSeconds(30), Collections.emptyMap(), "trace-1");
        ToolDefinition definition = new ToolDefinition("tool-1", "tool-v1", "skill", 1, "Skill",
                ToolProtocol.LOCAL_SKILL, "{}", "{}", ToolRiskLevel.LOW, false, true, 500,
                Collections.singletonList(AiPermission.AGENT_RUN), "{}", true);
        ToolInvocation invocation = new ToolInvocation("call-1", "skill", 1, execution,
                JsonNodeFactory.instance.objectNode().put("email", "email@example.com"),
                "idem-1", false, "node-1", "approval-1");
        ToolResult result = ToolResult.success(JsonNodeFactory.instance.objectNode().put("ok", true), 4)
                .withAuditDetails(new ToolAuditDetails("VALID", "approval-1", 2, "CLOSED", 500,
                        16, Arrays.asList("artifact-1"), Arrays.asList("citation-1")));

        adapter.record(definition, invocation, result, 1000, 4);

        assertTrue(jdbc.sql.contains("retry_count"));
        assertEquals("node-1", jdbc.args[4]);
        assertEquals("call-1", jdbc.args[5]);
        assertEquals("VALID", jdbc.args[11]);
        assertEquals(2, jdbc.args[13]);
        assertEquals(16L, jdbc.args[16]);
        assertEquals(ToolCallStatus.SUCCEEDED.name(), jdbc.args[20]);
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args;

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return 1;
        }
    }
}
