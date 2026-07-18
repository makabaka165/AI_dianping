package com.hmdp.ai.application.prompt;

import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.VersionDiffResponse;
import com.hmdp.ai.application.dto.prompt.CreatePromptRequest;
import com.hmdp.ai.application.dto.prompt.CreatePromptVersionRequest;
import com.hmdp.ai.application.dto.prompt.PromptResponse;
import com.hmdp.ai.application.dto.prompt.PromptRollbackRequest;
import com.hmdp.ai.application.dto.prompt.PromptVersionResponse;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.prompt.PromptDefinition;
import com.hmdp.ai.domain.prompt.PromptPublishValidator;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PromptApplicationService {
    private final PromptRepository repository;
    private final PromptPublishValidator publishValidator;
    private final AiAccessGuard accessGuard;
    private final AiIdGenerator idGenerator;
    private final ContentHashService hashes;
    private final VersionDiffService diffs;

    public PromptApplicationService(PromptRepository repository, PromptPublishValidator publishValidator,
                                    AiAccessGuard accessGuard, AiIdGenerator idGenerator,
                                    ContentHashService hashes, VersionDiffService diffs) {
        this.repository = repository;
        this.publishValidator = publishValidator;
        this.accessGuard = accessGuard;
        this.idGenerator = idGenerator;
        this.hashes = hashes;
        this.diffs = diffs;
    }

    @Transactional
    public PromptResponse create(CreatePromptRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.PROMPT_MANAGE);
        PromptDefinition prompt = new PromptDefinition(idGenerator.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getCode(), request.getName(),
                request.getDescription(), 0, "ACTIVE", null, null);
        return new PromptResponse(repository.create(prompt, context.getUserId()));
    }

    public PageResponse<PromptResponse> list(int page, int size) {
        AiSecurityContext context = accessGuard.require(AiPermission.PROMPT_MANAGE);
        int offset = Math.multiplyExact(page - 1, size);
        List<PromptResponse> items = repository.findPage(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), offset, size).stream()
                .map(PromptResponse::new).collect(Collectors.toList());
        return new PageResponse<>(items, repository.count(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId()), page, size);
    }

    @Transactional
    public PromptVersionResponse createVersion(String promptId, CreatePromptVersionRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.PROMPT_MANAGE);
        PromptDefinition prompt = requirePrompt(context, promptId);
        int versionNumber = repository.lockAndNextVersion(prompt.getTenantId(), prompt.getWorkspaceId(), prompt.getId());
        PromptVersion version = draft(prompt, versionNumber, request, request.getChangeNote());
        return new PromptVersionResponse(repository.createVersion(version, context.getUserId()));
    }

    public List<PromptVersionResponse> versions(String promptId, int page, int size) {
        AiSecurityContext context = accessGuard.require(AiPermission.PROMPT_MANAGE);
        int offset = Math.multiplyExact(page - 1, size);
        return repository.findVersions(context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(),
                        promptId, offset, size).stream().map(PromptVersionResponse::new).collect(Collectors.toList());
    }

    @Transactional
    public PromptVersionResponse publish(String promptId, int version) {
        AiSecurityContext context = accessGuard.require(AiPermission.PROMPT_MANAGE);
        PromptVersion target = requireVersion(context, promptId, version);
        ValidationResult result = publishValidator.validate(target);
        if (!result.isValid()) {
            throw new AiPlatformException(ErrorCode.AI_PUBLISH_VALIDATION_FAILED,
                    "prompt version cannot be published", result.getIssues());
        }
        return new PromptVersionResponse(repository.publish(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), promptId, version, context.getUserId()));
    }

    @Transactional
    public PromptVersionResponse rollback(String promptId, int sourceVersion, PromptRollbackRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.PROMPT_MANAGE);
        PromptDefinition prompt = requirePrompt(context, promptId);
        PromptVersion source = requireVersion(context, prompt.getId(), sourceVersion);
        int next = repository.lockAndNextVersion(prompt.getTenantId(), prompt.getWorkspaceId(), prompt.getId());
        CreatePromptVersionRequest copy = copy(source);
        PromptVersion draft = draft(prompt, next, copy,
                "Rollback from version " + sourceVersion + ": " + request.getChangeNote());
        PromptVersion created = repository.createVersion(draft, context.getUserId());
        ValidationResult validation = publishValidator.validate(created);
        if (!validation.isValid()) {
            throw new AiPlatformException(ErrorCode.AI_PUBLISH_VALIDATION_FAILED,
                    "rollback version cannot be published", validation.getIssues());
        }
        return new PromptVersionResponse(repository.publish(prompt.getTenantId(), prompt.getWorkspaceId(),
                prompt.getId(), next, context.getUserId()));
    }

    public VersionDiffResponse diff(String promptId, int leftVersion, int rightVersion) {
        AiSecurityContext context = accessGuard.require(AiPermission.PROMPT_MANAGE);
        PromptVersion left = requireVersion(context, promptId, leftVersion);
        PromptVersion right = requireVersion(context, promptId, rightVersion);
        return new VersionDiffResponse(leftVersion, rightVersion,
                diffs.diff(content(left), content(right)));
    }

    private PromptVersion draft(PromptDefinition prompt, int versionNumber, CreatePromptVersionRequest request,
                                String changeNote) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("systemPrompt", request.getSystemPrompt());
        content.put("taskPrompt", request.getTaskPrompt());
        content.put("toolInstruction", request.getToolInstruction());
        content.put("retrievalInstruction", request.getRetrievalInstruction());
        content.put("outputInstruction", request.getOutputInstruction());
        content.put("variablesSchema", request.getVariablesSchema());
        content.put("inputSchema", request.getInputSchema());
        content.put("outputSchema", request.getOutputSchema());
        content.put("examplesJson", request.getExamplesJson());
        return new PromptVersion(idGenerator.nextId(), prompt.getTenantId(), prompt.getWorkspaceId(), prompt.getId(),
                versionNumber, request.getSystemPrompt(), request.getTaskPrompt(), request.getToolInstruction(),
                request.getRetrievalInstruction(), request.getOutputInstruction(), request.getVariablesSchema(),
                request.getInputSchema(), request.getOutputSchema(), request.getExamplesJson(), VersionStatus.DRAFT,
                hashes.sha256(content), changeNote, null, null, null);
    }

    private PromptDefinition requirePrompt(AiSecurityContext context, String promptId) {
        return repository.findById(context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(), promptId)
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND, "prompt not found"));
    }

    private PromptVersion requireVersion(AiSecurityContext context, String promptId, int version) {
        return repository.findVersion(context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(),
                        promptId, version)
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND,
                        "prompt version not found"));
    }

    private CreatePromptVersionRequest copy(PromptVersion source) {
        CreatePromptVersionRequest request = new CreatePromptVersionRequest();
        request.setSystemPrompt(source.getSystemPrompt());
        request.setTaskPrompt(source.getTaskPrompt());
        request.setToolInstruction(source.getToolInstruction());
        request.setRetrievalInstruction(source.getRetrievalInstruction());
        request.setOutputInstruction(source.getOutputInstruction());
        request.setVariablesSchema(source.getVariablesSchema());
        request.setInputSchema(source.getInputSchema());
        request.setOutputSchema(source.getOutputSchema());
        request.setExamplesJson(source.getExamplesJson());
        request.setChangeNote(source.getChangeNote());
        return request;
    }

    private Map<String, Object> content(PromptVersion version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("systemPrompt", version.getSystemPrompt());
        result.put("taskPrompt", version.getTaskPrompt());
        result.put("toolInstruction", version.getToolInstruction());
        result.put("retrievalInstruction", version.getRetrievalInstruction());
        result.put("outputInstruction", version.getOutputInstruction());
        result.put("variablesSchema", version.getVariablesSchema());
        result.put("inputSchema", version.getInputSchema());
        result.put("outputSchema", version.getOutputSchema());
        result.put("examplesJson", version.getExamplesJson());
        return result;
    }
}
