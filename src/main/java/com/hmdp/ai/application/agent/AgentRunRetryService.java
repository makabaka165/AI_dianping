package com.hmdp.ai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentRunCreatedResponse;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.application.agent.AgentRuntime;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AgentRunRetryService {
    private final RunRepository repository;
    private final AgentRuntime runtime;
    private final AiIdGenerator idGenerator;
    private final ExecutionBudgetFactory budgets;
    private final ObjectMapper objectMapper;

    public AgentRunRetryService(RunRepository repository, AgentRuntime runtime, AiIdGenerator idGenerator,
                                ExecutionBudgetFactory budgets, ObjectMapper objectMapper) {
        this.repository = repository;
        this.runtime = runtime;
        this.idGenerator = idGenerator;
        this.budgets = budgets;
        this.objectMapper = objectMapper;
    }

    public AgentRunCreatedResponse retry(AiSecurityContext context, AgentRunRecord source) {
        if (source.getStatus() != RunStatus.FAILED && source.getStatus() != RunStatus.TIMED_OUT) {
            throw new AiPlatformException(ErrorCode.AI_RUN_NOT_RETRYABLE,
                    "only failed or timed out runs can be retried");
        }
        ExecutionBudget budget = budgets.fromStoredJson(source.getBudgetJson());
        Instant now = Instant.now();
        String runId = idGenerator.nextId();
        AgentRunRecord retry = new AgentRunRecord(runId, source.getTenantId(), source.getWorkspaceId(),
                context.getUserId(), source.getSessionId(), source.getConversationId(), source.getAgentId(),
                source.getAgentVersion(), RunStatus.QUEUED, source.getResponseMode(), source.getInputJson(), null,
                source.getMetadataJson(), source.getVersionSnapshotJson(), source.getBudgetJson(),
                authorizationJson(context), idGenerator.nextId(), source.getId(), source.getAttempt() + 1,
                null, null, now, null, null, now.plus(budget.getMaxRunDuration()), null);
        repository.create(retry);
        runtime.enqueue(retry.getTenantId(), retry.getWorkspaceId(), retry.getId());
        return new AgentRunCreatedResponse(runId, RunStatus.QUEUED, source.getAgentId(), agentCode(source), source.getAgentVersion());
    }

    private String agentCode(AgentRunRecord source) {
        try { return objectMapper.readTree(source.getVersionSnapshotJson()).path("agentCode").asText(source.getAgentId()); }
        catch (Exception ignored) { return source.getAgentId(); }
    }

    private String authorizationJson(AiSecurityContext context) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<String> permissions = context.getAuthorization().getPermissions().stream()
                .map(AiPermission::name).sorted().collect(Collectors.toList());
        snapshot.put("permissions", permissions);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("authorization snapshot cannot be serialized", e);
        }
    }
}
