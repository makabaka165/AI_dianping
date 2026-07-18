package com.hmdp.ai.application.dto.model;

import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelType;

import java.math.BigDecimal;
import java.time.Instant;

public final class ModelProfileResponse {
    private final String id;
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

    public ModelProfileResponse(ModelProfile profile) {
        this.id = profile.getId();
        this.code = profile.getCode();
        this.name = profile.getName();
        this.provider = profile.getProvider();
        this.modelName = profile.getModelName();
        this.baseUrl = profile.getBaseUrl();
        this.secretRef = profile.getSecretRef();
        this.modelType = profile.getModelType();
        this.capabilitiesJson = profile.getCapabilitiesJson();
        this.defaultParametersJson = profile.getDefaultParametersJson();
        this.contextWindow = profile.getContextWindow();
        this.maxOutputTokens = profile.getMaxOutputTokens();
        this.timeoutMs = profile.getTimeoutMs();
        this.retryPolicyJson = profile.getRetryPolicyJson();
        this.fallbackModelProfileId = profile.getFallbackModelProfileId();
        this.inputTokenPrice = profile.getInputTokenPrice();
        this.outputTokenPrice = profile.getOutputTokenPrice();
        this.enabled = profile.isEnabled();
        this.revision = profile.getRevision();
        this.status = profile.getStatus();
        this.createdAt = profile.getCreatedAt();
        this.updatedAt = profile.getUpdatedAt();
    }

    public String getId() { return id; }
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
