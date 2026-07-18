package com.hmdp.ai.application.dto.model;

import com.hmdp.ai.domain.model.ModelType;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

public class CreateModelProfileRequest {
    @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,63}") private String code;
    @NotBlank @Size(max = 128) private String name;
    @NotBlank @Size(max = 64) private String provider;
    @NotBlank @Size(max = 128) private String modelName;
    @NotBlank @Size(max = 512) private String baseUrl;
    @NotBlank @Pattern(regexp = "env:[A-Z][A-Z0-9_]{1,127}") private String secretRef;
    @NotNull private ModelType modelType;
    @NotBlank @Size(max = 8000) private String capabilitiesJson;
    @NotBlank @Size(max = 16000) private String defaultParametersJson;
    @Min(1) @Max(10000000) private int contextWindow;
    @Min(1) @Max(1000000) private int maxOutputTokens;
    @Min(100) @Max(300000) private int timeoutMs;
    @NotBlank @Size(max = 8000) private String retryPolicyJson;
    @Size(max = 64) private String fallbackModelProfileId;
    private BigDecimal inputTokenPrice = BigDecimal.ZERO;
    private BigDecimal outputTokenPrice = BigDecimal.ZERO;
    private boolean enabled = true;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getSecretRef() { return secretRef; }
    public void setSecretRef(String secretRef) { this.secretRef = secretRef; }
    public ModelType getModelType() { return modelType; }
    public void setModelType(ModelType modelType) { this.modelType = modelType; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
    public String getDefaultParametersJson() { return defaultParametersJson; }
    public void setDefaultParametersJson(String defaultParametersJson) { this.defaultParametersJson = defaultParametersJson; }
    public int getContextWindow() { return contextWindow; }
    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public String getRetryPolicyJson() { return retryPolicyJson; }
    public void setRetryPolicyJson(String retryPolicyJson) { this.retryPolicyJson = retryPolicyJson; }
    public String getFallbackModelProfileId() { return fallbackModelProfileId; }
    public void setFallbackModelProfileId(String fallbackModelProfileId) { this.fallbackModelProfileId = fallbackModelProfileId; }
    public BigDecimal getInputTokenPrice() { return inputTokenPrice; }
    public void setInputTokenPrice(BigDecimal inputTokenPrice) { this.inputTokenPrice = inputTokenPrice; }
    public BigDecimal getOutputTokenPrice() { return outputTokenPrice; }
    public void setOutputTokenPrice(BigDecimal outputTokenPrice) { this.outputTokenPrice = outputTokenPrice; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
