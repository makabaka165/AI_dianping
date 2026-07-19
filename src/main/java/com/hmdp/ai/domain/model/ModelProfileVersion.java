package com.hmdp.ai.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Immutable model configuration snapshot used by an agent version and a run. */
public final class ModelProfileVersion {
    private final String id;
    private final String tenantId;
    private final String workspaceId;
    private final String modelProfileId;
    private final int version;
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
    private final String fallbackModelProfileVersionId;
    private final BigDecimal inputTokenPrice;
    private final BigDecimal outputTokenPrice;
    private final String contentHash;
    private final String changeNote;
    private final String status;
    private final Instant publishedAt;
    private final String publishedBy;
    private final String createdBy;
    private final String updatedBy;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ModelProfileVersion(String id, String tenantId, String workspaceId, String modelProfileId,
                               int version, String provider, String modelName, String baseUrl,
                               String secretRef, ModelType modelType, String capabilitiesJson,
                               String defaultParametersJson, int contextWindow, int maxOutputTokens,
                               int timeoutMs, String retryPolicyJson, String fallbackModelProfileVersionId,
                               BigDecimal inputTokenPrice, BigDecimal outputTokenPrice, String contentHash,
                               String changeNote, String status, Instant publishedAt, String publishedBy,
                               String createdBy, String updatedBy, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.modelProfileId = Objects.requireNonNull(modelProfileId, "modelProfileId");
        this.version = version;
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
        this.fallbackModelProfileVersionId = fallbackModelProfileVersionId;
        this.inputTokenPrice = inputTokenPrice == null ? BigDecimal.ZERO : inputTokenPrice;
        this.outputTokenPrice = outputTokenPrice == null ? BigDecimal.ZERO : outputTokenPrice;
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
        this.changeNote = Objects.requireNonNull(changeNote, "changeNote");
        this.status = Objects.requireNonNull(status, "status");
        this.publishedAt = publishedAt;
        this.publishedBy = publishedBy;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getModelProfileId() { return modelProfileId; }
    public int getVersion() { return version; }
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
    public String getFallbackModelProfileVersionId() { return fallbackModelProfileVersionId; }
    public BigDecimal getInputTokenPrice() { return inputTokenPrice; }
    public BigDecimal getOutputTokenPrice() { return outputTokenPrice; }
    public String getContentHash() { return contentHash; }
    public String getChangeNote() { return changeNote; }
    public String getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getPublishedBy() { return publishedBy; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
