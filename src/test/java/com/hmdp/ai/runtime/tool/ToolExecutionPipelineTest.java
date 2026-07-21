package com.hmdp.ai.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.tool.ToolAuditPort;
import com.hmdp.ai.domain.tool.ToolBudgetPort;
import com.hmdp.ai.domain.tool.ToolCallStatus;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolDefinitionRepository;
import com.hmdp.ai.domain.tool.ToolIdempotencyPort;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolProtocol;
import com.hmdp.ai.domain.tool.ToolRateLimitPort;
import com.hmdp.ai.domain.tool.ToolResult;
import com.hmdp.ai.domain.tool.ToolRiskLevel;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutionPipelineTest {
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setup() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(5);
        executor.initialize();
    }

    @AfterEach
    void stop() {
        executor.shutdown();
    }

    @Test
    void validatesPermissionSchemaRateLimitBudgetAndStructuredOutput() {
        ObjectMapper mapper = new ObjectMapper();
        ToolDefinitionRepository repository = mock(ToolDefinitionRepository.class);
        ToolRateLimitPort rateLimit = mock(ToolRateLimitPort.class);
        ToolBudgetPort budget = mock(ToolBudgetPort.class);
        ToolIdempotencyPort idempotency = mock(ToolIdempotencyPort.class);
        ToolAuditPort audit = mock(ToolAuditPort.class);
        ToolDefinition definition = definition(ToolRiskLevel.LOW);
        when(repository.findBound(anyString(), anyString(), anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(Optional.of(definition));
        when(rateLimit.acquire(anyString(), anyString(), anyInt())).thenReturn(true);
        when(budget.reserve(anyString(), anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenReturn(true);
        when(idempotency.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        ToolExecutionPipeline pipeline = pipeline(mapper, repository, rateLimit, budget, idempotency, audit);

        ObjectNode input = mapper.createObjectNode().put("value", "ok");
        ToolResult result = pipeline.execute(new ToolInvocation("call", "test-skill", 1, context(), input));

        assertEquals(ToolCallStatus.SUCCEEDED, result.getStatus());
        assertEquals("ok", result.getData().path("echo").asText());
        assertNotNull(result.getAuditDetails());
        assertEquals("VALID", result.getAuditDetails().getInputSchemaValidationResult());
        assertEquals(1000, result.getAuditDetails().getTimeoutMs());
        assertEquals(0, result.getAuditDetails().getRetryCount());
        org.junit.jupiter.api.Assertions.assertTrue(result.getAuditDetails().getResultSizeBytes() > 0);
        verify(idempotency).store(anyString(), anyString(), anyString(), eq(result.getData()), any(Duration.class));
        verify(audit).record(eq(definition), any(), eq(result), anyLongValue(), anyLongValue());
    }

    @Test
    void highRiskToolRequiresApprovalBeforeExecution() {
        ObjectMapper mapper = new ObjectMapper();
        ToolDefinitionRepository repository = mock(ToolDefinitionRepository.class);
        ToolDefinition definition = definition(ToolRiskLevel.HIGH);
        when(repository.findBound(anyString(), anyString(), anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(Optional.of(definition));
        ToolAuditPort audit = mock(ToolAuditPort.class);
        ToolExecutionPipeline pipeline = pipeline(mapper, repository, mock(ToolRateLimitPort.class),
                mock(ToolBudgetPort.class), mock(ToolIdempotencyPort.class), audit);

        ToolResult result = pipeline.execute(new ToolInvocation("call", "test-skill", 1, context(),
                mapper.createObjectNode().put("value", "ok")));

        assertEquals(ToolCallStatus.APPROVAL_REQUIRED, result.getStatus());
        assertEquals("TOOL_APPROVAL_REQUIRED", result.getErrorCode());
        verify(audit).record(eq(definition), any(), eq(result), anyLongValue(), anyLongValue());
    }

    @Test
    void timesOutSlowToolAndReturnsRetryableStructuredFailure() {
        ObjectMapper mapper = new ObjectMapper();
        ToolDefinitionRepository repository = mock(ToolDefinitionRepository.class);
        ToolRateLimitPort rateLimit = mock(ToolRateLimitPort.class);
        ToolBudgetPort budget = mock(ToolBudgetPort.class);
        ToolIdempotencyPort idempotency = mock(ToolIdempotencyPort.class);
        ToolDefinition definition = definition(ToolRiskLevel.LOW, "slow-skill", 10);
        when(repository.findBound(anyString(), anyString(), anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(Optional.of(definition));
        when(rateLimit.acquire(anyString(), anyString(), anyInt())).thenReturn(true);
        when(budget.reserve(anyString(), anyString(), anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        when(idempotency.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        ToolResult result = pipeline(mapper, repository, rateLimit, budget, idempotency, mock(ToolAuditPort.class))
                .execute(new ToolInvocation("call", "slow-skill", 1, context(),
                        mapper.createObjectNode().put("value", "ok")));
        assertEquals(ToolCallStatus.TIMED_OUT, result.getStatus());
        assertEquals("TOOL_TIMEOUT", result.getErrorCode());
        assertEquals(true, result.isRetryable());
    }

    private ToolExecutionPipeline pipeline(ObjectMapper mapper, ToolDefinitionRepository repository,
                                           ToolRateLimitPort rateLimit, ToolBudgetPort budget,
                                           ToolIdempotencyPort idempotency, ToolAuditPort audit) {
        return new ToolExecutionPipeline(repository,
                new LocalSkillRegistry(Arrays.asList(new TestSkill(), new SlowSkill())),
                Collections.emptyList(),
                new ToolPermissionService(), rateLimit, budget, idempotency, audit,
                new JsonSchemaValidationService(mapper), mapper, executor,
                new ToolReliabilityExecutor(mapper));
    }

    private ToolDefinition definition(ToolRiskLevel risk) {
        return definition(risk, "test-skill", 1000);
    }

    private ToolDefinition definition(ToolRiskLevel risk, String code, int timeoutMs) {
        return new ToolDefinition("tool", "tool-v1", code, 1, "Test",
                ToolProtocol.LOCAL_SKILL,
                "{\"type\":\"object\",\"required\":[\"value\"]}",
                "{\"type\":\"object\",\"required\":[\"echo\"]}",
                risk, false, true, timeoutMs, "{\"maxAttempts\":1}",
                Collections.singletonList(AiPermission.AGENT_RUN), "{}", true);
    }

    @AgentSkill(code = "test-skill")
    static class TestSkill implements LocalSkill {
        @Override
        public JsonNode execute(ExecutionContext context, JsonNode input) {
            return JsonNodeFactory.instance.objectNode().put("echo", input.path("value").asText());
        }
    }

    @AgentSkill(code = "slow-skill")
    static class SlowSkill implements LocalSkill {
        @Override
        public JsonNode execute(ExecutionContext context, JsonNode input) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return JsonNodeFactory.instance.objectNode().put("echo", input.path("value").asText());
        }
    }

    private ExecutionContext context() {
        return new ExecutionContext("t", "w", "u", "s", null, "r", "a", 1,
                "zh-CN", "UTC", Collections.emptyList(), Collections.emptyList(),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), ExecutionBudget.defaults(),
                Instant.now().plusSeconds(30), Collections.emptyMap(), "trace");
    }

    private static long anyLongValue() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
