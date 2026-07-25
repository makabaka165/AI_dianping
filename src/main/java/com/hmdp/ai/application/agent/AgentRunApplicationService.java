package com.hmdp.ai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.agent.AgentRunCreatedResponse;
import com.hmdp.ai.application.dto.agent.AgentRunDetailResponse;
import com.hmdp.ai.application.dto.agent.AgentRunEventResponse;
import com.hmdp.ai.application.dto.agent.AgentRunRequest;
import com.hmdp.ai.application.dto.agent.AgentRunSummaryResponse;
import com.hmdp.ai.application.dto.agent.ResumeAgentRunRequest;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.RunEvent;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.agent.AgentRuntime;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.application.agent.event.SseRunEventHub;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AgentRunApplicationService {
    private final RunRepository repository;
    private final AgentDefinitionLoader definitionLoader;
    private final AgentRunRequestValidator requestValidator;
    private final AgentRunAccessPolicy accessPolicy;
    private final AgentRunCancellationService cancellationService;
    private final AgentRunRetryService retryService;
    private final AgentRunResumeService resumeService;
    private final AgentRuntime runtime;
    private final SseRunEventHub eventHub;
    private final AiAccessGuard accessGuard;
    private final AiIdGenerator idGenerator;
    private final ContentHashService hashes;
    private final ExecutionBudgetFactory budgets;
    private final ObjectMapper objectMapper;

    public AgentRunApplicationService(RunRepository repository, AgentDefinitionLoader definitionLoader,
                                      AgentRunRequestValidator requestValidator, AgentRunAccessPolicy accessPolicy,
                                      AgentRunCancellationService cancellationService,
                                      AgentRunRetryService retryService, AgentRunResumeService resumeService,
                                      AgentRuntime runtime, SseRunEventHub eventHub, AiAccessGuard accessGuard,
                                      AiIdGenerator idGenerator, ContentHashService hashes,
                                      ExecutionBudgetFactory budgets, ObjectMapper objectMapper) {
        this.repository = repository;
        this.definitionLoader = definitionLoader;
        this.requestValidator = requestValidator;
        this.accessPolicy = accessPolicy;
        this.cancellationService = cancellationService;
        this.retryService = retryService;
        this.resumeService = resumeService;
        this.runtime = runtime;
        this.eventHub = eventHub;
        this.accessGuard = accessGuard;
        this.idGenerator = idGenerator;
        this.hashes = hashes;
        this.budgets = budgets;
        this.objectMapper = objectMapper;
    }

    public AgentRunCreatedResponse create(AgentRunRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_RUN);
        PublishedAgentDefinition definition = definitionLoader.load(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getAgentId(), request.getAgentVersion());
        if (definition.getVersion().getStatus() != VersionStatus.PUBLISHED) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND,
                    "agent version is not currently published");
        }
        requestValidator.validate(definition, request);
        ExecutionBudget budget = budgets.fromPolicy(definition.getVersion().getExecutionPolicyJson());
        Instant now = Instant.now();
        String runId = idGenerator.nextId();
        AgentRunRecord run = new AgentRunRecord(runId, context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), context.getUserId(), request.getSessionId(),
                conversationId(context, definition, request.getSessionId()), definition.getAgent().getId(),
                definition.getVersion().getVersion(), RunStatus.QUEUED, request.getResponseMode().name(),
                json(request.getInput()), null, json(request.getMetadata()), json(definition.getVersionSnapshot()),
                budgets.snapshotJson(budget), authorizationJson(context), idGenerator.nextId(), null, 1,
                null, null, now, null, null, now.plus(budget.getMaxRunDuration()), null);
        repository.create(run);
        runtime.enqueue(run.getTenantId(), run.getWorkspaceId(), run.getId());
        return new AgentRunCreatedResponse(runId, RunStatus.QUEUED,
                definition.getAgent().getId(), definition.getAgent().getCode(),
                definition.getVersion().getVersion());
    }

    public AgentRunDetailResponse get(String runId) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_RUN);
        AgentRunRecord run = requireRun(context, runId);
        return detail(run);
    }

    public PageResponse<AgentRunSummaryResponse> list(int page, int size, String agentId, String status,
                                                      Instant createdFrom, Instant createdTo) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_RUN);
        int offset = Math.multiplyExact(page - 1, size);
        String ownerFilter = context.getAuthorization().has(AiPermission.RUN_INSPECT)
                || context.getAuthorization().has(AiPermission.ADMIN)
                ? null
                : context.getUserId();
        RunStatus statusFilter = parseStatus(status);
        String tenantId = context.getTenant().getTenantId();
        String workspaceId = context.getWorkspace().getWorkspaceId();
        List<AgentRunSummaryResponse> items = repository.findPage(tenantId, workspaceId, ownerFilter, agentId,
                        statusFilter, createdFrom, createdTo, offset, size).stream()
                .map(AgentRunSummaryResponse::new)
                .collect(Collectors.toList());
        long total = repository.countPage(tenantId, workspaceId, ownerFilter, agentId, statusFilter,
                createdFrom, createdTo);
        return new PageResponse<>(items, total, page, size);
    }

    public AgentRunCreatedResponse cancel(String runId) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_RUN);
        AgentRunRecord run = requireRun(context, runId);
        return cancellationService.cancel(context, run);
    }

    public AgentRunCreatedResponse retry(String runId) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_RUN);
        AgentRunRecord run = requireRun(context, runId);
        return retryService.retry(context, run);
    }

    public AgentRunCreatedResponse resume(String runId, ResumeAgentRunRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_RUN);
        AgentRunRecord run = requireRun(context, runId);
        return resumeService.resume(context, run, request);
    }

    public List<AgentRunEventResponse> events(String runId, long afterSequence, int limit) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_RUN);
        AgentRunRecord run = requireRun(context, runId);
        return repository.findEvents(run.getTenantId(), run.getWorkspaceId(), runId, afterSequence, limit).stream()
                .map(this::event).collect(Collectors.toList());
    }

    public SseEmitter openEvents(String runId, long afterSequence) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_RUN);
        AgentRunRecord run = requireRun(context, runId);
        return eventHub.open(run.getTenantId(), run.getWorkspaceId(), runId, afterSequence,
                () -> repository.latestEventSequence(run.getTenantId(), run.getWorkspaceId(), runId),
                sequence -> repository.findEvents(run.getTenantId(), run.getWorkspaceId(), runId, sequence, 1000)
                        .stream().map(this::event).collect(Collectors.toList()),
                run.getStatus().isTerminal());
    }

    private AgentRunRecord requireRun(AiSecurityContext context, String runId) {
        AgentRunRecord run = repository.find(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), runId)
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "run not found"));
        accessPolicy.requireRead(context, run);
        return run;
    }

    private RunStatus parseStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        try {
            return RunStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AiPlatformException(ErrorCode.PARAM_ERROR, "invalid run status");
        }
    }

    private AgentRunDetailResponse detail(AgentRunRecord run) {
        try {
            JsonNode snapshot = objectMapper.readTree(run.getVersionSnapshotJson());
            JsonNode output = run.getOutputJson() == null ? null : objectMapper.readTree(run.getOutputJson());
            return new AgentRunDetailResponse(run, snapshot, output);
        } catch (Exception e) {
            throw new IllegalStateException("stored run JSON is invalid", e);
        }
    }

    private AgentRunEventResponse event(RunEvent event) {
        try {
            return new AgentRunEventResponse(event, objectMapper.readTree(event.getPayloadJson()));
        } catch (Exception e) {
            throw new IllegalStateException("stored run event JSON is invalid", e);
        }
    }

    private String conversationId(AiSecurityContext context, PublishedAgentDefinition definition, String sessionId) {
        String scope = context.getTenant().getTenantId() + ':' + context.getWorkspace().getWorkspaceId() + ':'
                + context.getUserId() + ':' + definition.getAgent().getId() + ':' + sessionId;
        return hashes.sha256(scope).substring(0, 32);
    }

    private String authorizationJson(AiSecurityContext context) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<String> permissions = context.getAuthorization().getPermissions().stream()
                .map(AiPermission::name).sorted().collect(Collectors.toList());
        snapshot.put("permissions", permissions);
        return json(snapshot);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("request contains non-serializable data", e);
        }
    }
}
