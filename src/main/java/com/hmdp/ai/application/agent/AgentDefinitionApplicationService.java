package com.hmdp.ai.application.agent;

import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.VersionDiffResponse;
import com.hmdp.ai.application.dto.agent.AgentResponse;
import com.hmdp.ai.application.dto.agent.AgentRollbackRequest;
import com.hmdp.ai.application.dto.agent.AgentVersionResponse;
import com.hmdp.ai.application.dto.agent.CreateAgentRequest;
import com.hmdp.ai.application.dto.agent.CreateAgentVersionRequest;
import com.hmdp.ai.application.dto.agent.PublishValidationResponse;
import com.hmdp.ai.application.dto.agent.RunnableAgentResponse;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.AgentDependencyInspector;
import com.hmdp.ai.domain.agent.AgentPublishValidator;
import com.hmdp.ai.domain.agent.AgentRepository;
import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.json.VersionDiffService;
import com.hmdp.ai.shared.validation.ValidationResult;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AgentDefinitionApplicationService {
    private final AgentRepository repository;
    private final AgentDependencyInspector dependencies;
    private final AgentPublishValidator publishValidator;
    private final AiAccessGuard accessGuard;
    private final AiIdGenerator idGenerator;
    private final ContentHashService hashes;
    private final VersionDiffService diffs;

    public AgentDefinitionApplicationService(AgentRepository repository, AgentDependencyInspector dependencies,
                                             AgentPublishValidator publishValidator, AiAccessGuard accessGuard,
                                             AiIdGenerator idGenerator, ContentHashService hashes,
                                             VersionDiffService diffs) {
        this.repository = repository;
        this.dependencies = dependencies;
        this.publishValidator = publishValidator;
        this.accessGuard = accessGuard;
        this.idGenerator = idGenerator;
        this.hashes = hashes;
        this.diffs = diffs;
    }

    @Transactional
    public AgentResponse create(CreateAgentRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_MANAGE);
        AgentDefinition agent = new AgentDefinition(idGenerator.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getCode(), request.getName(),
                request.getDescription(), 0, "ACTIVE", null, null);
        return new AgentResponse(repository.create(agent, context.getUserId()));
    }

    public PageResponse<AgentResponse> list(int page, int size) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_MANAGE);
        int offset = Math.multiplyExact(page - 1, size);
        List<AgentResponse> items = repository.findPage(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), offset, size).stream()
                .map(AgentResponse::new).collect(Collectors.toList());
        return new PageResponse<>(items, repository.count(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId()), page, size);
    }

    public PageResponse<RunnableAgentResponse> listRunnable(int page, int size) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_RUN);
        int offset = Math.multiplyExact(page - 1, size);
        String tenantId = context.getTenant().getTenantId();
        String workspaceId = context.getWorkspace().getWorkspaceId();
        List<RunnableAgentResponse> items = repository.findRunnablePage(tenantId, workspaceId, offset, size).stream()
                .map(agent -> {
                    int publishedVersion = repository.findPublishedVersion(tenantId, workspaceId, agent.getId())
                            .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND,
                                    "published agent version not found"));
                    return new RunnableAgentResponse(agent.getId(), agent.getCode(), agent.getName(),
                            agent.getDescription(), publishedVersion);
                })
                .collect(Collectors.toList());
        return new PageResponse<>(items, repository.countRunnable(tenantId, workspaceId), page, size);
    }

    public AgentResponse get(String agentId) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_MANAGE);
        return new AgentResponse(requireAgent(context, agentId));
    }

    @Transactional
    public AgentVersionResponse createVersion(String agentId, CreateAgentVersionRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_MANAGE);
        AgentDefinition agent = requireAgent(context, agentId);
        requireUnique(request.getToolVersionIds(), "toolVersionIds");
        requireUnique(request.getKnowledgeBaseVersionIds(), "knowledgeBaseVersionIds");
        int versionNumber = repository.lockAndNextVersion(agent.getTenantId(), agent.getWorkspaceId(), agent.getId());
        AgentVersion version = draft(agent, versionNumber, request, request.getChangeNote());
        return new AgentVersionResponse(repository.createVersion(version, request.getToolVersionIds(),
                request.getKnowledgeBaseVersionIds(), context.getUserId()));
    }

    public List<AgentVersionResponse> versions(String agentId, int page, int size) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_MANAGE);
        int offset = Math.multiplyExact(page - 1, size);
        return repository.findVersions(context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(),
                        agentId, offset, size).stream().map(AgentVersionResponse::new).collect(Collectors.toList());
    }

    public AgentVersionResponse version(String agentId, int version) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_MANAGE);
        return new AgentVersionResponse(requireVersion(context, agentId, version));
    }

    public PublishValidationResponse validate(String agentId, int version) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_MANAGE);
        return new PublishValidationResponse(publishValidator.validate(requireVersion(context, agentId, version)));
    }

    @Transactional
    public AgentVersionResponse publish(String agentId, int version) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_MANAGE);
        AgentVersion target = requireVersion(context, agentId, version);
        ValidationResult validation = publishValidator.validate(target);
        if (!validation.isValid()) {
            throw new AiPlatformException(ErrorCode.AI_PUBLISH_VALIDATION_FAILED,
                    "agent version cannot be published", validation.getIssues());
        }
        return new AgentVersionResponse(repository.publish(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), agentId, version, context.getUserId()));
    }

    @Transactional
    public AgentVersionResponse rollback(String agentId, int sourceVersion, AgentRollbackRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_MANAGE);
        AgentDefinition agent = requireAgent(context, agentId);
        AgentVersion source = requireVersion(context, agent.getId(), sourceVersion);
        List<String> toolIds = dependencies.rawToolVersionIds(agent.getTenantId(), agent.getWorkspaceId(), source.getId());
        List<String> knowledgeIds = dependencies.rawKnowledgeVersionIds(agent.getTenantId(), agent.getWorkspaceId(),
                source.getId());
        int next = repository.lockAndNextVersion(agent.getTenantId(), agent.getWorkspaceId(), agent.getId());
        CreateAgentVersionRequest copy = copy(source, toolIds, knowledgeIds);
        AgentVersion draft = draft(agent, next, copy,
                "Rollback from version " + sourceVersion + ": " + request.getChangeNote());
        AgentVersion created = repository.createVersion(draft, toolIds, knowledgeIds, context.getUserId());
        ValidationResult validation = publishValidator.validate(created);
        if (!validation.isValid()) {
            throw new AiPlatformException(ErrorCode.AI_PUBLISH_VALIDATION_FAILED,
                    "rollback version cannot be published", validation.getIssues());
        }
        return new AgentVersionResponse(repository.publish(agent.getTenantId(), agent.getWorkspaceId(),
                agent.getId(), next, context.getUserId()));
    }

    public VersionDiffResponse diff(String agentId, int leftVersion, int rightVersion) {
        AiSecurityContext context = accessGuard.require(AiPermission.AGENT_MANAGE);
        AgentVersion left = requireVersion(context, agentId, leftVersion);
        AgentVersion right = requireVersion(context, agentId, rightVersion);
        return new VersionDiffResponse(leftVersion, rightVersion, diffs.diff(content(left), content(right)));
    }

    private AgentVersion draft(AgentDefinition agent, int versionNumber, CreateAgentVersionRequest request,
                               String changeNote) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", request.getName());
        content.put("description", request.getDescription());
        content.put("modelProfileId", request.getModelProfileId());
        content.put("modelProfileVersionId", request.getModelProfileVersionId());
        content.put("promptVersionId", request.getPromptVersionId());
        content.put("workflowVersionId", request.getWorkflowVersionId());
        content.put("memoryPolicyJson", request.getMemoryPolicyJson());
        content.put("inputSchema", request.getInputSchema());
        content.put("outputSchema", request.getOutputSchema());
        content.put("executionPolicyJson", request.getExecutionPolicyJson());
        content.put("responseRenderPolicyJson", request.getResponseRenderPolicyJson());
        content.put("toolVersionIds", new ArrayList<>(request.getToolVersionIds()));
        content.put("knowledgeBaseVersionIds", new ArrayList<>(request.getKnowledgeBaseVersionIds()));
        String modelProfileId = request.getModelProfileId();
        String modelProfileVersionId = request.getModelProfileVersionId();
        if ((modelProfileId == null || modelProfileId.trim().isEmpty())
                && modelProfileVersionId != null) modelProfileId = modelProfileVersionId;
        if (modelProfileVersionId == null || modelProfileVersionId.trim().isEmpty()) {
            modelProfileVersionId = modelProfileId;
        }
        return new AgentVersion(idGenerator.nextId(), agent.getTenantId(), agent.getWorkspaceId(), agent.getId(),
                versionNumber, request.getName(), request.getDescription(), modelProfileId, modelProfileVersionId,
                request.getPromptVersionId(), request.getWorkflowVersionId(), request.getMemoryPolicyJson(),
                request.getInputSchema(), request.getOutputSchema(), request.getExecutionPolicyJson(),
                request.getResponseRenderPolicyJson(), VersionStatus.DRAFT, hashes.sha256(content), changeNote,
                null, null, null);
    }

    private AgentDefinition requireAgent(AiSecurityContext context, String agentId) {
        return repository.findById(context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(), agentId)
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "agent not found"));
    }

    private AgentVersion requireVersion(AiSecurityContext context, String agentId, int version) {
        return repository.findVersion(context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(),
                        agentId, version)
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND,
                        "agent version not found"));
    }

    private void requireUnique(List<String> values, String field) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " cannot contain duplicates");
        }
    }

    private CreateAgentVersionRequest copy(AgentVersion source, List<String> toolIds, List<String> knowledgeIds) {
        CreateAgentVersionRequest request = new CreateAgentVersionRequest();
        request.setName(source.getName());
        request.setDescription(source.getDescription());
        request.setModelProfileId(source.getModelProfileId());
        request.setModelProfileVersionId(source.getModelProfileVersionId());
        request.setPromptVersionId(source.getPromptVersionId());
        request.setWorkflowVersionId(source.getWorkflowVersionId());
        request.setMemoryPolicyJson(source.getMemoryPolicyJson());
        request.setInputSchema(source.getInputSchema());
        request.setOutputSchema(source.getOutputSchema());
        request.setExecutionPolicyJson(source.getExecutionPolicyJson());
        request.setResponseRenderPolicyJson(source.getResponseRenderPolicyJson());
        request.setToolVersionIds(new ArrayList<>(toolIds));
        request.setKnowledgeBaseVersionIds(new ArrayList<>(knowledgeIds));
        request.setChangeNote(source.getChangeNote());
        return request;
    }

    private Map<String, Object> content(AgentVersion version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", version.getName());
        result.put("description", version.getDescription());
        result.put("modelProfileId", version.getModelProfileId());
        result.put("modelProfileVersionId", version.getModelProfileVersionId());
        result.put("promptVersionId", version.getPromptVersionId());
        result.put("workflowVersionId", version.getWorkflowVersionId());
        result.put("memoryPolicyJson", version.getMemoryPolicyJson());
        result.put("inputSchema", version.getInputSchema());
        result.put("outputSchema", version.getOutputSchema());
        result.put("executionPolicyJson", version.getExecutionPolicyJson());
        result.put("responseRenderPolicyJson", version.getResponseRenderPolicyJson());
        return result;
    }
}
