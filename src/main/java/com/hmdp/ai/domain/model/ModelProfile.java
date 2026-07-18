package com.hmdp.ai.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class ModelProfile {
    private final String id;
    private final String tenantId;
    private final String workspaceId;
    private final String code;
    private final String name;
    private final String provider;
    private final String modelName;
    private final String baseUrl;
    private final String secretRef;
    private final ModelType modelType;
    private final String capabilitiesJson;
    private final String defaultParametersJson;
    private final int contextWindow;
    private final int maxOutputTokens;
    private final int timeoutMs;
    private final String retryPolicyJson;
    private final String fallbackModelProfileId;
    private final BigDecimal inputTokenPrice;
    private final BigDecimal outputTokenPrice;
    private final boolean enabled;
    private final int revision;
    private final String status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ModelProfile(String id, String tenantId, String workspaceId, String code, String name,
                        String provider, String modelName, String baseUrl, String secretRef, ModelType modelType,
                        String capabilitiesJson, String defaultParametersJson, int contextWindow,
                        int maxOutputTokens, int timeoutMs, String retryPolicyJson,
                        String fallbackModelProfileId, BigDecimal inputTokenPrice, BigDecimal outputTokenPrice,
                        boolean enabled, int revision, String status, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.secretRef = Objects.requireNonNull(secretRef, "secretRef");
        this.modelType = Objects.requireNonNull(modelType, "modelType");
        this.capabilitiesJson = Objects.requireNonNull(capabilitiesJson, "capabilitiesJson");
        this.defaultParametersJson = Objects.requireNonNull(defaultParametersJson, "defaultParametersJson");
        this.contextWindow = contextWindow;
        this.maxOutputTokens = maxOutputTokens;
        this.timeoutMs = timeoutMs;
        this.retryPolicyJson = Objects.requireNonNull(retryPolicyJson, "retryPolicyJson");
        this.fallbackModelProfileId = fallbackModelProfileId;
        this.inputTokenPrice = inputTokenPrice == null ? BigDecimal.ZERO : inputTokenPrice;
        this.outputTokenPrice = outputTokenPrice == null ? BigDecimal.ZERO : outputTokenPrice;
        this.enabled = enabled;
        this.revision = revision;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getProvider() { return provider; }
    public String getModelName() { return modelName; }
    public String getBaseUrl() { return baseUrl; }
    public String getSecretRef() { return secretRef; }
    public ModelType getModelType() { return modelType; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public String getDefaultParametersJson() { return defaultParametersJson; }
    public int getContextWindow() { return contextWindow; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public int getTimeoutMs() { return timeoutMs; }
    public String getRetryPolicyJson() { return retryPolicyJson; }
    public String getFallbackModelProfileId() { return fallbackModelProfileId; }
    public BigDecimal getInputTokenPrice() { return inputTokenPrice; }
    public BigDecimal getOutputTokenPrice() { return outputTokenPrice; }
    public boolean isEnabled() { return enabled; }
    public int getRevision() { return revision; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
