package com.hmdp.ai.application.dto.model;

import com.hmdp.ai.domain.model.ModelProfileVersion;

import java.math.BigDecimal;
import java.time.Instant;

public final class ModelProfileVersionResponse {
    private final String id;
    private final String modelProfileId;
    private final int version;
    private final String provider;
    private final String modelName;
    private final String baseUrl;
    private final String secretRef;
    private final String modelType;
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

    public ModelProfileVersionResponse(ModelProfileVersion value) {
        this.id = value.getId();
        this.modelProfileId = value.getModelProfileId();
        this.version = value.getVersion();
        this.provider = value.getProvider();
        this.modelName = value.getModelName();
        this.baseUrl = value.getBaseUrl();
        this.secretRef = value.getSecretRef();
        this.modelType = value.getModelType().name();
        this.capabilitiesJson = value.getCapabilitiesJson();
        this.defaultParametersJson = value.getDefaultParametersJson();
        this.contextWindow = value.getContextWindow();
        this.maxOutputTokens = value.getMaxOutputTokens();
        this.timeoutMs = value.getTimeoutMs();
        this.retryPolicyJson = value.getRetryPolicyJson();
        this.fallbackModelProfileVersionId = value.getFallbackModelProfileVersionId();
        this.inputTokenPrice = value.getInputTokenPrice();
        this.outputTokenPrice = value.getOutputTokenPrice();
        this.contentHash = value.getContentHash();
        this.changeNote = value.getChangeNote();
        this.status = value.getStatus();
        this.publishedAt = value.getPublishedAt();
    }

    public String getId() { return id; }
    public String getModelProfileId() { return modelProfileId; }
    public int getVersion() { return version; }
    public String getProvider() { return provider; }
    public String getModelName() { return modelName; }
    public String getBaseUrl() { return baseUrl; }
    public String getSecretRef() { return secretRef; }
    public String getModelType() { return modelType; }
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
}
