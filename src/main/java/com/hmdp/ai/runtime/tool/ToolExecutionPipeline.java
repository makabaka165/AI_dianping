package com.hmdp.ai.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.approval.ApprovalRequest;
import com.hmdp.ai.domain.tool.ToolAuditDetails;
import com.hmdp.ai.domain.tool.ToolAuditPort;
import com.hmdp.ai.domain.tool.ToolBudgetPort;
import com.hmdp.ai.domain.tool.ToolCallStatus;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolDefinitionRepository;
import com.hmdp.ai.domain.tool.ToolIdempotencyPort;
import com.hmdp.ai.domain.tool.ToolInvocation;
import com.hmdp.ai.domain.tool.ToolProtocol;
import com.hmdp.ai.domain.tool.ToolProtocolAdapter;
import com.hmdp.ai.domain.tool.ToolRateLimitPort;
import com.hmdp.ai.domain.tool.ToolResult;
import com.hmdp.ai.domain.tool.ToolRiskLevel;
import com.hmdp.ai.runtime.cancellation.RunCancellationRegistry;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import com.hmdp.ai.shared.validation.ValidationResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutionPipeline {
  private static final int DEFAULT_RATE_LIMIT = 10;
  private static final int DEFAULT_MAX_RESULT_BYTES = 1024 * 1024;
  private static final Duration DEFAULT_IDEMPOTENCY_TTL = Duration.ofHours(24);

  private final ToolDefinitionRepository definitions;
  private final LocalSkillRegistry skills;
  private final List<ToolProtocolAdapter> protocolAdapters;
  private final ToolPermissionService permissions;
  private final ToolRateLimitPort rateLimits;
  private final ToolBudgetPort budgets;
  private final ToolIdempotencyPort idempotency;
  private final ToolAuditPort audit;
  private final JsonSchemaValidationService schemas;
  private final ObjectMapper mapper;
  private final ThreadPoolTaskExecutor executor;
  private final ToolReliabilityExecutor reliability;
  private final ApprovalService approvals;
  private final ContentHashService hashes;
  private final RunCancellationRegistry cancellations;

  @org.springframework.beans.factory.annotation.Autowired
  public ToolExecutionPipeline(
      ToolDefinitionRepository definitions,
      LocalSkillRegistry skills,
      List<ToolProtocolAdapter> protocolAdapters,
      ToolPermissionService permissions,
      ToolRateLimitPort rateLimits,
      ToolBudgetPort budgets,
      ToolIdempotencyPort idempotency,
      ToolAuditPort audit,
      JsonSchemaValidationService schemas,
      ObjectMapper mapper,
      @Qualifier("toolExecutionExecutor") ThreadPoolTaskExecutor executor,
      ToolReliabilityExecutor reliability,
      ApprovalService approvals,
      ContentHashService hashes,
      RunCancellationRegistry cancellations) {
    this.definitions = definitions;
    this.skills = skills;
    this.protocolAdapters = protocolAdapters;
    this.permissions = permissions;
    this.rateLimits = rateLimits;
    this.budgets = budgets;
    this.idempotency = idempotency;
    this.audit = audit;
    this.schemas = schemas;
    this.mapper = mapper;
    this.executor = executor;
    this.reliability = reliability;
    this.approvals = approvals;
    this.hashes = hashes;
    this.cancellations = cancellations;
  }

  public ToolExecutionPipeline(
      ToolDefinitionRepository definitions,
      LocalSkillRegistry skills,
      List<ToolProtocolAdapter> protocolAdapters,
      ToolPermissionService permissions,
      ToolRateLimitPort rateLimits,
      ToolBudgetPort budgets,
      ToolIdempotencyPort idempotency,
      ToolAuditPort audit,
      JsonSchemaValidationService schemas,
      ObjectMapper mapper,
      ThreadPoolTaskExecutor executor,
      ToolReliabilityExecutor reliability) {
    this(
        definitions,
        skills,
        protocolAdapters,
        permissions,
        rateLimits,
        budgets,
        idempotency,
        audit,
        schemas,
        mapper,
        executor,
        reliability,
        null,
        null,
        null);
  }

  public ToolResult execute(ToolInvocation invocation) {
    if (cancellations != null
        && cancellations.token(invocation.getContext().getRunId()) != null
        && cancellations.token(invocation.getContext().getRunId()).isCancelled()) {
      return ToolResult.failure(ToolCallStatus.CANCELLED, "RUN_CANCELLED", "run cancelled", false);
    }
    long started = System.currentTimeMillis();
    ToolDefinition definition =
        definitions
            .findBound(
                invocation.getContext().getTenantId(),
                invocation.getContext().getWorkspaceId(),
                invocation.getContext().getAgentId(),
                invocation.getContext().getAgentVersion(),
                invocation.getToolCode(),
                invocation.getToolVersion())
            .orElse(null);
    if (definition == null) {
      return ToolResult.failure(
          ToolCallStatus.DENIED,
          "TOOL_NOT_BOUND",
          "tool is not bound to this agent version",
          false);
    }

    AuditContext auditContext =
        new AuditContext(definition.getTimeoutMs(), invocation.getApprovalRequestId());
    ToolResult result;
    if (!definition.isEnabled()) {
      result =
          ToolResult.failure(ToolCallStatus.DENIED, "TOOL_DISABLED", "tool is disabled", false);
    } else if (!permissions.allowed(invocation.getContext(), definition)) {
      result =
          ToolResult.failure(
              ToolCallStatus.DENIED, "TOOL_PERMISSION_DENIED", "tool permission denied", false);
    } else {
      ValidationResult inputValidation =
          schemas.validateValue(definition.getInputSchema(), invocation.getInput(), "toolInput");
      auditContext.inputSchemaValidation = inputValidation.isValid() ? "VALID" : "INVALID";
      if (!inputValidation.isValid()) {
        result =
            ToolResult.failure(
                ToolCallStatus.FAILED,
                "TOOL_INPUT_SCHEMA_INVALID",
                "tool input schema validation failed",
                false);
      } else if (requiresApproval(definition) && !invocation.isApproved()) {
        if (approvals == null) {
          result =
              ToolResult.failure(
                  ToolCallStatus.APPROVAL_REQUIRED,
                  "TOOL_APPROVAL_REQUIRED",
                  "high-risk tool requires TOOL_APPROVE and a matching inputHash",
                  false);
        } else {
          ApprovalRequest request =
              approvals.request(definition, invocation, invocation.getInput());
          auditContext.approvalRequestId = request.getId();
          result =
              ToolResult.failure(
                  ToolCallStatus.APPROVAL_REQUIRED,
                  "TOOL_APPROVAL_REQUIRED",
                  "approvalRequestId="
                      + request.getId()
                      + ";inputHash="
                      + request.getInputHash()
                      + ";requires=TOOL_APPROVE",
                  false);
        }
      } else if (requiresApproval(definition)
          && (approvals == null
              || !approvals.approved(definition, invocation, invocation.getInput()))) {
        result =
            ToolResult.failure(
                ToolCallStatus.DENIED,
                "TOOL_APPROVAL_INVALID",
                "approval is missing, expired, self-approved, or inputHash changed",
                false);
      } else {
        result = executeAuthorized(definition, invocation, started, auditContext);
      }
    }
    result = result.withAuditDetails(auditContext.details(result, mapper));
    if (definition != null) {
      audit.record(definition, invocation, result, started, System.currentTimeMillis() - started);
    }
    return result;
  }

  private ToolResult executeAuthorized(
      ToolDefinition definition,
      ToolInvocation invocation,
      long started,
      AuditContext auditContext) {
    JsonNode configuration = configuration(definition);
    String idempotencyKey = definition.getVersionId() + ':' + invocation.getIdempotencyKey();
    if (definition.isIdempotent()) {
      Optional<JsonNode> cached =
          idempotency.find(
              invocation.getContext().getTenantId(),
              invocation.getContext().getWorkspaceId(),
              idempotencyKey);
      if (cached.isPresent())
        return ToolResult.success(cached.get(), System.currentTimeMillis() - started);
    }
    Duration budgetTtl = Duration.between(Instant.now(), invocation.getContext().getDeadline());
    if (budgetTtl.isNegative() || budgetTtl.isZero()) {
      return ToolResult.failure(
          ToolCallStatus.TIMED_OUT, "RUN_DEADLINE_EXCEEDED", "run deadline exceeded", false);
    }
    if (!budgets.reserve(
        invocation.getContext().getTenantId(),
        invocation.getContext().getWorkspaceId(),
        invocation.getContext().getRunId(),
        invocation.getContext().getExecutionBudget().getMaxToolCalls(),
        budgetTtl)) {
      return ToolResult.failure(
          ToolCallStatus.DENIED, "TOOL_BUDGET_EXCEEDED", "tool call budget exceeded", false);
    }
    int permits = Math.max(1, configuration.path("rateLimitPerSecond").asInt(DEFAULT_RATE_LIMIT));
    if (!rateLimits.acquire(invocation.getContext().getTenantId(), definition.getId(), permits)) {
      return ToolResult.failure(
          ToolCallStatus.DENIED, "TOOL_RATE_LIMITED", "tool rate limit exceeded", true);
    }

    ToolResult result = invoke(definition, invocation, configuration, started, auditContext);
    if (result.getStatus() == ToolCallStatus.SUCCEEDED && definition.isIdempotent()) {
      long seconds =
          Math.max(
              60,
              configuration
                  .path("idempotencyTtlSeconds")
                  .asLong(DEFAULT_IDEMPOTENCY_TTL.getSeconds()));
      idempotency.store(
          invocation.getContext().getTenantId(),
          invocation.getContext().getWorkspaceId(),
          idempotencyKey,
          result.getData(),
          Duration.ofSeconds(seconds));
    }
    return result;
  }

  private ToolResult invoke(
      ToolDefinition definition,
      ToolInvocation invocation,
      JsonNode configuration,
      long started,
      AuditContext auditContext) {
    AtomicInteger attempts = new AtomicInteger();
    Callable<ToolResult> operation =
        () -> {
          attempts.incrementAndGet();
          return executeProtocol(definition, invocation, configuration);
        };
    Callable<ToolResult> decorated = reliability.decorate(definition, invocation, operation);
    Future<ToolResult> future = executor.submit(decorated);
    if (cancellations != null) cancellations.track(invocation.getContext().getRunId(), future);
    try {
      long remainingMs =
          Duration.between(Instant.now(), invocation.getContext().getDeadline()).toMillis();
      long timeoutMs = Math.max(1, Math.min(definition.getTimeoutMs(), remainingMs));
      auditContext.timeoutMs = (int) Math.min(Integer.MAX_VALUE, timeoutMs);
      ToolResult protocolResult = future.get(timeoutMs, TimeUnit.MILLISECONDS);
      if (protocolResult.getStatus() != ToolCallStatus.SUCCEEDED) {
        return protocolResult;
      }
      JsonNode data = protocolResult.getData();
      int resultLimit =
          Math.max(
              1024,
              Math.min(
                  configuration.path("maxResultBytes").asInt(DEFAULT_MAX_RESULT_BYTES),
                  (int)
                      Math.min(
                          Integer.MAX_VALUE,
                          invocation.getContext().getExecutionBudget().getMaxArtifactBytes())));
      int resultSize = mapper.writeValueAsString(data).getBytes(StandardCharsets.UTF_8).length;
      auditContext.resultSizeBytes = resultSize;
      if (resultSize > resultLimit) {
        return ToolResult.failure(
            ToolCallStatus.FAILED,
            "TOOL_RESULT_TOO_LARGE",
            "tool result exceeds configured size limit",
            false);
      }
      ValidationResult validation =
          schemas.validateValue(definition.getOutputSchema(), data, "toolOutput");
      return validation.isValid()
          ? ToolResult.success(
              data,
              protocolResult.getCitations(),
              protocolResult.getArtifacts(),
              protocolResult.getWarnings(),
              protocolResult.getUsage())
          : ToolResult.failure(
              ToolCallStatus.FAILED,
              "TOOL_OUTPUT_SCHEMA_INVALID",
              "tool output schema validation failed",
              false);
    } catch (TimeoutException e) {
      future.cancel(true);
      return ToolResult.failure(
          ToolCallStatus.TIMED_OUT, "TOOL_TIMEOUT", "tool execution timed out", true);
    } catch (java.util.concurrent.CancellationException e) {
      future.cancel(true);
      return ToolResult.failure(ToolCallStatus.CANCELLED, "RUN_CANCELLED", "run cancelled", false);
    } catch (ExecutionException e) {
      return ToolResult.failure(
          ToolCallStatus.FAILED, "TOOL_EXECUTION_FAILED", "tool execution failed", true);
    } catch (Exception e) {
      return ToolResult.failure(
          ToolCallStatus.FAILED, "TOOL_EXECUTION_FAILED", "tool execution failed", false);
    } finally {
      auditContext.retryCount = Math.max(0, attempts.get() - 1);
      auditContext.circuitBreakerState = reliability.circuitBreakerState(definition, invocation);
      if (cancellations != null) cancellations.untrack(invocation.getContext().getRunId(), future);
    }
  }

  private ToolResult executeProtocol(
      ToolDefinition definition, ToolInvocation invocation, JsonNode configuration) {
    if (definition.getProtocol() == ToolProtocol.LOCAL_SKILL) {
      JsonNode data =
          skills
              .require(definition.getCode(), definition.getVersion())
              .execute(invocation.getContext(), invocation.getInput());
      return ToolResult.success(data, 0);
    }
    return protocolAdapters.stream()
        .filter(adapter -> adapter.protocol() == definition.getProtocol())
        .findFirst()
        .map(adapter -> adapter.execute(definition, invocation, configuration))
        .orElseGet(
            () ->
                ToolResult.failure(
                    ToolCallStatus.FAILED,
                    "TOOL_PROTOCOL_NOT_AVAILABLE",
                    "tool protocol adapter is not configured",
                    false));
  }

  private boolean requiresApproval(ToolDefinition definition) {
    return definition.getRiskLevel() == ToolRiskLevel.HIGH
        || definition.getRiskLevel() == ToolRiskLevel.CRITICAL;
  }

  private JsonNode configuration(ToolDefinition definition) {
    try {
      return mapper.readTree(definition.getConfigurationJson());
    } catch (Exception e) {
      throw new IllegalStateException("stored tool configuration is invalid", e);
    }
  }

  private static final class AuditContext {
    private String inputSchemaValidation = "NOT_EVALUATED";
    private String approvalRequestId;
    private int retryCount;
    private String circuitBreakerState = "NOT_INVOKED";
    private int timeoutMs;
    private long resultSizeBytes;

    private AuditContext(int timeoutMs, String approvalRequestId) {
      this.timeoutMs = timeoutMs;
      this.approvalRequestId = approvalRequestId;
    }

    private ToolAuditDetails details(ToolResult result, ObjectMapper mapper) {
      long size = resultSizeBytes;
      if (size == 0 && result.getData() != null) {
        try {
          size = mapper.writeValueAsBytes(result.getData()).length;
        } catch (Exception ignored) {
          size = 0;
        }
      }
      return new ToolAuditDetails(
          inputSchemaValidation,
          approvalRequestId,
          retryCount,
          circuitBreakerState,
          timeoutMs,
          size,
          result.getArtifacts().stream()
              .map(value -> value.getArtifactId())
              .collect(Collectors.toList()),
          result.getCitations().stream()
              .map(value -> value.getCitationId())
              .collect(Collectors.toList()));
    }
  }
}
