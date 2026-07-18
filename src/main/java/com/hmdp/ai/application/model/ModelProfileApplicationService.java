package com.hmdp.ai.application.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.model.CreateModelProfileRequest;
import com.hmdp.ai.application.dto.model.ModelHealthResponse;
import com.hmdp.ai.application.dto.model.ModelProfileResponse;
import com.hmdp.ai.application.dto.model.UpdateModelProfileRequest;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelHealthChecker;
import com.hmdp.ai.domain.model.ModelProfileRepository;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ModelProfileApplicationService {
    private final ModelProfileRepository repository;
    private final AiAccessGuard accessGuard;
    private final AiIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ModelHealthChecker healthChecker;

    public ModelProfileApplicationService(ModelProfileRepository repository, AiAccessGuard accessGuard,
                                          AiIdGenerator idGenerator, ObjectMapper objectMapper,
                                          ModelHealthChecker healthChecker) {
        this.repository = repository;
        this.accessGuard = accessGuard;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.healthChecker = healthChecker;
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
        return new ModelProfileResponse(repository.create(profile, context.getUserId()));
    }

    @Transactional
    public ModelProfileResponse update(String id, UpdateModelProfileRequest request) {
        AiSecurityContext context = accessGuard.require(AiPermission.MODEL_MANAGE);
        ModelProfile current = require(context, id);
        validate(request.getBaseUrl(), request.getSecretRef(), request.getCapabilitiesJson(),
                request.getDefaultParametersJson(), request.getRetryPolicyJson(),
                request.getInputTokenPrice(), request.getOutputTokenPrice());
        ModelProfile profile = new ModelProfile(current.getId(), current.getTenantId(), current.getWorkspaceId(),
                current.getCode(), request.getName(), request.getProvider(), request.getModelName(),
                request.getBaseUrl(), request.getSecretRef(), request.getModelType(), request.getCapabilitiesJson(),
                request.getDefaultParametersJson(), request.getContextWindow(), request.getMaxOutputTokens(),
                request.getTimeoutMs(), request.getRetryPolicyJson(), request.getFallbackModelProfileId(),
                request.getInputTokenPrice(), request.getOutputTokenPrice(), request.isEnabled(),
                current.getRevision(), current.getStatus(), current.getCreatedAt(), current.getUpdatedAt());
        return new ModelProfileResponse(repository.update(profile, request.getExpectedRevision(), context.getUserId()));
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
