package com.hmdp.ai.domain.tool;

import com.hmdp.ai.domain.security.AiPermission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ToolDefinition {
    private final String id;
    private final String versionId;
    private final String code;
    private final String name;
    private final String inputSchema;
    private final String outputSchema;
    private final String retryPolicyJson;
    private final String configurationJson;
    private final int version;
    private final int timeoutMs;
    private final ToolProtocol protocol;
    private final ToolRiskLevel riskLevel;
    private final boolean sideEffect;
    private final boolean idempotent;
    private final boolean enabled;
    private final List<AiPermission> requiredPermissions;

    public ToolDefinition(String id, String versionId, String code, int version, String name,
                          ToolProtocol protocol, String inputSchema, String outputSchema,
                          ToolRiskLevel riskLevel, boolean sideEffect, boolean idempotent, int timeoutMs,
                          List<AiPermission> requiredPermissions, String configurationJson, boolean enabled) {
        this(id, versionId, code, version, name, protocol, inputSchema, outputSchema, riskLevel, sideEffect,
                idempotent, timeoutMs, "{\"maxAttempts\":1}", requiredPermissions, configurationJson, enabled);
    }

    public ToolDefinition(String id, String versionId, String code, int version, String name,
                          ToolProtocol protocol, String inputSchema, String outputSchema,
                          ToolRiskLevel riskLevel, boolean sideEffect, boolean idempotent, int timeoutMs,
                          String retryPolicyJson, List<AiPermission> requiredPermissions,
                          String configurationJson, boolean enabled) {
        this.id = id;
        this.versionId = versionId;
        this.code = code;
        this.version = version;
        this.name = name;
        this.protocol = protocol;
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.riskLevel = riskLevel;
        this.sideEffect = sideEffect;
        this.idempotent = idempotent;
        this.timeoutMs = timeoutMs;
        this.retryPolicyJson = retryPolicyJson;
        this.requiredPermissions = Collections.unmodifiableList(new ArrayList<>(requiredPermissions));
        this.configurationJson = configurationJson;
        this.enabled = enabled;
    }

    public String getId() { return id; }
    public String getVersionId() { return versionId; }
    public String getCode() { return code; }
    public int getVersion() { return version; }
    public String getName() { return name; }
    public ToolProtocol getProtocol() { return protocol; }
    public String getInputSchema() { return inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public ToolRiskLevel getRiskLevel() { return riskLevel; }
    public boolean isSideEffect() { return sideEffect; }
    public boolean isIdempotent() { return idempotent; }
    public int getTimeoutMs() { return timeoutMs; }
    public String getRetryPolicyJson() { return retryPolicyJson; }
    public List<AiPermission> getRequiredPermissions() { return requiredPermissions; }
    public String getConfigurationJson() { return configurationJson; }
    public boolean isEnabled() { return enabled; }
}
