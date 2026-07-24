package com.hmdp.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.stp.StpInterface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.approval.ApprovalApplicationService;
import com.hmdp.ai.application.dto.approval.ApprovalResponse;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.application.security.AiAuthorizationService;
import com.hmdp.ai.application.security.AiSecurityContextHolder;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.ai.domain.tool.ToolCallStatus;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolIdempotencyPort;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolProtocol;
import com.hmdp.ai.domain.tool.ToolResult;
import com.hmdp.ai.domain.tool.ToolRiskLevel;
import com.hmdp.ai.infrastructure.observability.AiAuditService;
import com.hmdp.ai.integration.support.IntegrationMySqlContainer;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import com.hmdp.ai.runtime.tool.AgentSkill;
import com.hmdp.ai.runtime.tool.ApprovalService;
import com.hmdp.ai.runtime.tool.LocalSkill;
import com.hmdp.ai.runtime.tool.LocalSkillRegistry;
import com.hmdp.ai.runtime.tool.ToolExecutionPipeline;
import com.hmdp.ai.runtime.tool.ToolPermissionService;
import com.hmdp.ai.runtime.tool.ToolReliabilityExecutor;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class HighRiskToolApprovalIntegrationTest {
  private static final String TENANT = "default";
  private static final String WORKSPACE = "default";
  private static final String REQUESTER = "approval-requester";
  private static final String UNAUTHORIZED = "approval-unauthorized";
  private static final String APPROVER = "approval-reviewer";

  @Container
  static final IntegrationMySqlContainer MYSQL = new IntegrationMySqlContainer();

  private ThreadPoolTaskExecutor executor;

  @AfterEach
  void cleanUp() {
    AiSecurityContextHolder.clear();
    if (executor != null) {
      executor.shutdown();
    }
  }

  @Test
  void requiresIndependentAuthorizedDecisionBoundToTheExactToolInput() {
    MYSQL.migrateSchema();
    JdbcTemplate jdbc = jdbcTemplate();
    insertMember(jdbc, REQUESTER);
    insertMember(jdbc, UNAUTHORIZED);
    insertMember(jdbc, APPROVER);

    PermissionProvider permissionProvider = new PermissionProvider();
    permissionProvider.grant(REQUESTER, AiPermission.AGENT_RUN, AiPermission.TOOL_APPROVE);
    permissionProvider.grant(UNAUTHORIZED, AiPermission.AGENT_RUN);
    permissionProvider.grant(APPROVER, AiPermission.TOOL_APPROVE);
    AiAuthorizationService authorization = new AiAuthorizationService(jdbc, permissionProvider);
    AiAccessGuard access = new AiAccessGuard();
    AiIdGenerator ids = new AiIdGenerator();
    ObjectMapper mapper = new ObjectMapper();
    ContentHashService hashes = new ContentHashService(mapper);
    ApprovalService approvals = new ApprovalService(jdbc, ids, hashes);
    ApprovalApplicationService application =
        new ApprovalApplicationService(jdbc, access, ids, new AiAuditService(jdbc, ids));

    AtomicInteger executions = new AtomicInteger();
    ToolDefinition definition = highRiskTool();
    ToolExecutionPipeline pipeline =
        pipeline(mapper, authorization, approvals, definition, executions);
    JsonNode approvedInput = mapper.createObjectNode().put("value", "approved-value");
    ExecutionContext executionContext = executionContext(authorization);

    ToolResult pending =
        pipeline.execute(
            new ToolInvocation(
                "tool-call-1",
                definition.getCode(),
                definition.getVersion(),
                executionContext,
                approvedInput,
                "approval-idempotency",
                false,
                "node-run-1",
                null));

    assertThat(pending.getStatus()).isEqualTo(ToolCallStatus.APPROVAL_REQUIRED);
    assertThat(pending.getErrorCode()).isEqualTo("TOOL_APPROVAL_REQUIRED");
    String approvalRequestId = pending.getAuditDetails().getApprovalRequestId();
    assertThat(approvalRequestId).isNotBlank();
    assertThat(jdbc.queryForObject(
            "select input_hash from ai_approval_request where id=?", String.class, approvalRequestId))
        .isEqualTo(hashes.sha256(approvedInput.toString()));
    assertThat(executions).hasValue(0);

    bindSecurityContext(authorization, UNAUTHORIZED);
    assertThatThrownBy(() -> application.decide(approvalRequestId, "APPROVED", "not authorized"))
        .isInstanceOf(com.hmdp.exception.BusinessException.class);

    bindSecurityContext(authorization, REQUESTER);
    assertThatThrownBy(() -> application.decide(approvalRequestId, "APPROVED", "self approval"))
        .isInstanceOf(com.hmdp.exception.BusinessException.class);

    bindSecurityContext(authorization, APPROVER);
    ApprovalResponse approved = application.decide(approvalRequestId, "APPROVED", "reviewed");
    assertThat(approved.getStatus()).isEqualTo("APPROVED");
    assertThat(jdbc.queryForObject(
            "select count(*) from ai_approval_decision where approval_request_id=? and decided_by=?",
            Integer.class,
            approvalRequestId,
            APPROVER))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select count(*) from ai_audit_event where resource_type='APPROVAL_REQUEST'"
                + " and resource_id=? and action='APPROVAL_DECIDED'",
            Integer.class,
            approvalRequestId))
        .isEqualTo(1);

    AiSecurityContextHolder.clear();
    ToolResult executed =
        pipeline.execute(
            new ToolInvocation(
                "tool-call-2",
                definition.getCode(),
                definition.getVersion(),
                executionContext,
                approvedInput,
                "approval-idempotency",
                true,
                "node-run-2",
                approvalRequestId));
    assertThat(executed.getStatus()).isEqualTo(ToolCallStatus.SUCCEEDED);
    assertThat(executed.getData().path("echo").asText()).isEqualTo("approved-value");
    assertThat(executions).hasValue(1);

    ToolResult changedInput =
        pipeline.execute(
            new ToolInvocation(
                "tool-call-3",
                definition.getCode(),
                definition.getVersion(),
                executionContext,
                mapper.createObjectNode().put("value", "changed-value"),
                "changed-idempotency",
                true,
                "node-run-3",
                approvalRequestId));
    assertThat(changedInput.getStatus()).isEqualTo(ToolCallStatus.DENIED);
    assertThat(changedInput.getErrorCode()).isEqualTo("TOOL_APPROVAL_INVALID");
    assertThat(executions).hasValue(1);
  }

  private ToolExecutionPipeline pipeline(
      ObjectMapper mapper,
      AiAuthorizationService authorization,
      ApprovalService approvals,
      ToolDefinition definition,
      AtomicInteger executions) {
    executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(4);
    executor.setThreadNamePrefix("approval-integration-");
    executor.initialize();
    ToolIdempotencyPort idempotency =
        new ToolIdempotencyPort() {
          @Override
          public Optional<JsonNode> find(String tenantId, String workspaceId, String key) {
            return Optional.empty();
          }

          @Override
          public void store(
              String tenantId, String workspaceId, String key, JsonNode result, Duration ttl) {}
        };
    return new ToolExecutionPipeline(
        (tenantId, workspaceId, agentId, agentVersion, toolCode, toolVersion) ->
            Optional.of(definition),
        new LocalSkillRegistry(
            Collections.singletonList(new ApprovalTestSkill(executions))),
        Collections.emptyList(),
        new ToolPermissionService(authorization),
        (tenantId, toolId, permitsPerSecond) -> true,
        (tenantId, workspaceId, runId, maximumCalls, ttl) -> true,
        idempotency,
        (tool, invocation, result, startedAtMillis, durationMillis) -> {},
        new JsonSchemaValidationService(mapper),
        mapper,
        executor,
        new ToolReliabilityExecutor(mapper),
        approvals,
        new ContentHashService(mapper),
        new RunCancellationRegistry());
  }

  private JdbcTemplate jdbcTemplate() {
    return MYSQL.jdbcTemplate();
  }

  private void insertMember(JdbcTemplate jdbc, String userId) {
    jdbc.update(
        "insert into ai_workspace_member"
            + " (id,tenant_id,workspace_id,user_id,role,status,created_by,updated_by)"
            + " values (?,?,?,?,?,?,?,?)",
        "member-" + userId,
        TENANT,
        WORKSPACE,
        userId,
        "RUNNER",
        "ACTIVE",
        "test",
        "test");
  }

  private ToolDefinition highRiskTool() {
    return new ToolDefinition(
        "approval-tool",
        "approval-tool-version",
        "approval-test-skill",
        1,
        "Approval test skill",
        ToolProtocol.LOCAL_SKILL,
        "{\"type\":\"object\",\"required\":[\"value\"],"
            + "\"properties\":{\"value\":{\"type\":\"string\"}}}",
        "{\"type\":\"object\",\"required\":[\"echo\"],"
            + "\"properties\":{\"echo\":{\"type\":\"string\"}}}",
        ToolRiskLevel.HIGH,
        true,
        true,
        2_000,
        "{\"maxAttempts\":1}",
        Collections.singletonList(AiPermission.AGENT_RUN),
        "{}",
        true);
  }

  private ExecutionContext executionContext(AiAuthorizationService authorization) {
    AuthorizationContext current = authorization.authorize(REQUESTER, TENANT, WORKSPACE);
    return new ExecutionContext(
        TENANT,
        WORKSPACE,
        REQUESTER,
        "approval-session",
        "approval-conversation",
        "approval-run",
        "approval-agent",
        1,
        "zh-CN",
        "Asia/Shanghai",
        Collections.emptyList(),
        Collections.emptyList(),
        current,
        ExecutionBudget.defaults(),
        Instant.now().plusSeconds(60),
        Collections.emptyMap(),
        "approval-trace");
  }

  private void bindSecurityContext(AiAuthorizationService authorization, String userId) {
    AiSecurityContextHolder.set(
        new AiSecurityContext(
            userId,
            new TenantContext(TENANT),
            new WorkspaceContext(WORKSPACE),
            authorization.authorize(userId, TENANT, WORKSPACE),
            false));
  }

  @AgentSkill(code = "approval-test-skill")
  static final class ApprovalTestSkill implements LocalSkill {
    private final AtomicInteger executions;

    ApprovalTestSkill(AtomicInteger executions) {
      this.executions = executions;
    }

    @Override
    public JsonNode execute(ExecutionContext context, JsonNode input) {
      executions.incrementAndGet();
      return new ObjectMapper().createObjectNode().put("echo", input.path("value").asText());
    }
  }

  private static final class PermissionProvider implements StpInterface {
    private final Map<String, List<String>> permissions = new ConcurrentHashMap<>();

    void grant(String userId, AiPermission... values) {
      permissions.put(
          userId,
          Arrays.stream(values).map(AiPermission::name).collect(java.util.stream.Collectors.toList()));
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
      return permissions.getOrDefault(String.valueOf(loginId), Collections.emptyList());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
      return Collections.emptyList();
    }
  }
}
