package com.hmdp.ai.application.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.VersionDiffResponse;
import com.hmdp.ai.application.dto.model.CreateModelProfileRequest;
import com.hmdp.ai.application.dto.model.CreateModelProfileVersionRequest;
import com.hmdp.ai.application.dto.model.ModelHealthResponse;
import com.hmdp.ai.application.dto.model.ModelProfileResponse;
import com.hmdp.ai.application.dto.model.ModelProfileVersionResponse;
import com.hmdp.ai.application.dto.model.UpdateModelProfileRequest;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelHealthChecker;
import com.hmdp.ai.domain.model.ModelProfileRepository;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.model.ModelProfileVersionRepository;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.json.VersionDiffService;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ModelProfileApplicationService {
    private final ModelProfileRepository repository;
    private final AiAccessGuard accessGuard;
    private final AiIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ModelHealthChecker healthChecker;
    private final ModelProfileVersionRepository versions;
    private final ContentHashService hashes;
    private final VersionDiffService diffs;

    public ModelProfileApplicationService(ModelProfileRepository repository, AiAccessGuard accessGuard,
                                          AiIdGenerator idGenerator, ObjectMapper objectMapper,
                                          ModelHealthChecker healthChecker,
                                          ModelProfileVersionRepository versions,
                                          ContentHashService hashes, VersionDiffService diffs) {
        this.repository = repository;
        this.accessGuard = accessGuard;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.healthChecker = healthChecker;
        this.versions = versions;
        this.hashes = hashes;
        this.diffs = diffs;
    }

    @Transactional
    public ModelProfileResponse create(CreateModelProfileRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.MODEL_MANAGE);
        validate(request.getBaseUrl(), request.getSecretRef(), request.getCapabilitiesJson(),
                request.getDefaultParametersJson(), request.getRetryPolicyJson(),
                request.getInputTokenPrice(), request.getOutputTokenPrice());
        ModelProfile profile = new ModelProfile(idGenerator.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getCode(), request.getName(), request.getProvider(),
                request.getModelName(), request.getBaseUrl(), request.getSecretRef(), request.getModelType(),
                request.getCapabilitiesJson(), request.getDefaultParametersJson(), request.getContextWindow(),
                request.getMaxOutputTokens(), request.getTimeoutMs(), request.getRetryPolicyJson(),
                request.getFallbackModelProfileId(), request.getInputTokenPrice(), request.getOutputTokenPrice(),
                request.isEnabled(), 1, "ACTIVE", null, null);
        ModelProfile created = repository.create(profile, context.getUserId());
        versions.create(toVersion(created, 1, "Initial model profile version.", context.getUserId()),
                context.getUserId());
        return new ModelProfileResponse(created);
    }

    @Transactional
    public ModelProfileResponse update(String id, UpdateModelProfileRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.MODEL_MANAGE);
        ModelProfile current = require(context, id);
        validate(request.getBaseUrl(), request.getSecretRef(), request.getCapabilitiesJson(),
                request.getDefaultParametersJson(), request.getRetryPolicyJson(),
                request.getInputTokenPrice(), request.getOutputTokenPrice());
        int version = versions.nextVersion(current.getTenantId(), current.getWorkspaceId(), current.getId());
        versions.create(toVersion(current, request, version, "Compatibility update", context.getUserId()),
                context.getUserId());
        return new ModelProfileResponse(current);
    }

    public PageResponse<ModelProfileResponse> list(int page, int size) {
        AiSecurityContext context = accessGuard.require(AiPermission.MODEL_MANAGE);
        int offset = Math.multiplyExact(page - 1, size);
        List<ModelProfileResponse> items = repository.findPage(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), offset, size).stream()
                .map(ModelProfileResponse::new).collect(Collectors.toList());
        return new PageResponse<>(items, repository.count(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId()), page, size);
    }

    public ModelHealthResponse healthCheck(String id) {
        AiSecurityContext context = accessGuard.require(AiPermission.MODEL_MANAGE);
        return new ModelHealthResponse(healthChecker.check(require(context, id)));
    }

    @Transactional
    public ModelProfileVersionResponse createVersion(String id, CreateModelProfileVersionRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.MODEL_MANAGE);
        ModelProfile current = require(context, id);
        validate(request.getBaseUrl(), request.getSecretRef(), request.getCapabilitiesJson(),
                request.getDefaultParametersJson(), request.getRetryPolicyJson(),
                request.getInputTokenPrice(), request.getOutputTokenPrice());
        int version = versions.nextVersion(current.getTenantId(), current.getWorkspaceId(), id);
        return new ModelProfileVersionResponse(versions.create(
                toVersion(current, request, version, request.getChangeNote(), context.getUserId()),
                context.getUserId()));
    }

    public List<ModelProfileVersionResponse> versions(String id, int page, int size) {
        AiSecurityContext context = accessGuard.require(AiPermission.MODEL_MANAGE);
        return versions.findVersions(context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(),
                        id, Math.multiplyExact(page - 1, size), size).stream()
                .map(ModelProfileVersionResponse::new).collect(Collectors.toList());
    }

    public ModelProfileVersionResponse version(String id, int version) {
        AiSecurityContext context = accessGuard.require(AiPermission.MODEL_MANAGE);
        return new ModelProfileVersionResponse(versions.findByProfileAndVersion(
                context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(), id, version)
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND,
                        "model profile version not found")));
    }

    @Transactional
    public ModelProfileVersionResponse publishVersion(String id, int version) {
        AiSecurityContext context = accessGuard.require(AiPermission.MODEL_MANAGE);
        return new ModelProfileVersionResponse(versions.publish(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), id, version, context.getUserId()));
    }

    public VersionDiffResponse diff(String id, int left, int right) {
        AiSecurityContext context = accessGuard.require(AiPermission.MODEL_MANAGE);
        ModelProfileVersion a = versions.findByProfileAndVersion(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), id, left).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND, "left model profile version not found"));
        ModelProfileVersion b = versions.findByProfileAndVersion(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), id, right).orElseThrow(() ->
                new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND, "right model profile version not found"));
        return new VersionDiffResponse(left, right, diffs.diff(a, b));
    }

    private ModelProfileVersion toVersion(ModelProfile profile, int version, String changeNote, String actor) {
        return toVersion(profile, profile.getName(), profile.getProvider(), profile.getModelName(),
                profile.getBaseUrl(), profile.getSecretRef(), profile.getModelType(), profile.getCapabilitiesJson(),
                profile.getDefaultParametersJson(), profile.getContextWindow(), profile.getMaxOutputTokens(),
                profile.getTimeoutMs(), profile.getRetryPolicyJson(), profile.getFallbackModelProfileId(),
                profile.getInputTokenPrice(), profile.getOutputTokenPrice(), version, changeNote, actor);
    }

    private ModelProfileVersion toVersion(ModelProfile profile, UpdateModelProfileRequest request, int version,
                                          String changeNote, String actor) {
        return toVersion(profile, request.getName(), request.getProvider(), request.getModelName(),
                request.getBaseUrl(), request.getSecretRef(), request.getModelType(), request.getCapabilitiesJson(),
                request.getDefaultParametersJson(), request.getContextWindow(), request.getMaxOutputTokens(),
                request.getTimeoutMs(), request.getRetryPolicyJson(), request.getFallbackModelProfileId(),
                request.getInputTokenPrice(), request.getOutputTokenPrice(), version, changeNote, actor);
    }

    private ModelProfileVersion toVersion(ModelProfile profile, CreateModelProfileVersionRequest request, int version,
                                          String changeNote, String actor) {
        return toVersion(profile, (UpdateModelProfileRequest) request, version, changeNote, actor);
    }

    private ModelProfileVersion toVersion(ModelProfile profile, String name, String provider, String modelName,
                                          String baseUrl, String secretRef, com.hmdp.ai.domain.model.ModelType type,
                                          String capabilities, String parameters, int contextWindow,
                                          int maxOutputTokens, int timeoutMs, String retryPolicy,
                                          String fallback, BigDecimal inputPrice, BigDecimal outputPrice,
                                          int version, String changeNote, String actor) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("profileId", profile.getId());
        content.put("version", version);
        content.put("provider", provider);
        content.put("modelName", modelName);
        content.put("baseUrl", baseUrl);
        content.put("secretRef", secretRef);
        content.put("capabilities", capabilities);
        content.put("parameters", parameters);
        content.put("retryPolicy", retryPolicy);
        return new ModelProfileVersion(idGenerator.nextId(), profile.getTenantId(), profile.getWorkspaceId(),
                profile.getId(), version, provider, modelName, baseUrl, secretRef, type, capabilities, parameters,
                contextWindow, maxOutputTokens, timeoutMs, retryPolicy, fallback, inputPrice, outputPrice,
                hashes.sha256(content), changeNote, "DRAFT", null, null, actor, actor, Instant.now(), Instant.now());
    }

    private ModelProfile require(AiSecurityContext context, String id) {
        return repository.findById(context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(), id)
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,
                        "model profile not found"));
    }

    private void validate(String baseUrl, String secretRef, String capabilities, String parameters,
                          String retryPolicy, BigDecimal inputPrice, BigDecimal outputPrice) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("baseUrl is invalid");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must be an HTTP(S) endpoint without user information");
        }
        if (secretRef == null || !secretRef.matches("env:[A-Z][A-Z0-9_]{1,127}")) {
            throw new IllegalArgumentException("secretRef must use env:VARIABLE_NAME");
        }
        requireObject(capabilities, "capabilitiesJson");
        JsonNode capabilityNode = requireObject(capabilities, "capabilitiesJson");
        for (String name : new String[]{"streaming", "toolCalling", "jsonSchema", "vision", "longContext"}) {
            if (!capabilityNode.has(name) || !capabilityNode.get(name).isBoolean()) {
                throw new IllegalArgumentException("capabilitiesJson must contain boolean capability " + name);
            }
        }
        requireObject(parameters, "defaultParametersJson");
        requireObject(retryPolicy, "retryPolicyJson");
        if ((inputPrice != null && inputPrice.signum() < 0) || (outputPrice != null && outputPrice.signum() < 0)) {
            throw new IllegalArgumentException("token prices cannot be negative");
        }
    }

    private JsonNode requireObject(String json, String field) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) throw new IllegalArgumentException(field + " must be a JSON object");
            return node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " is invalid JSON");
        }
    }
}
