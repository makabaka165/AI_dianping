package com.hmdp.ai.domain.agent;

public final class AgentToolBinding {
    private final String toolId;
    private final int toolVersion;
    private final String toolVersionId;
    private final String protocol;
    private final String requiredPermissionsJson;
    private final boolean enabled;

    public AgentToolBinding(String toolId, int toolVersion, String toolVersionId, String protocol,
                            String requiredPermissionsJson, boolean enabled) {
        this.toolId = toolId;
        this.toolVersion = toolVersion;
        this.toolVersionId = toolVersionId;
        this.protocol = protocol;
        this.requiredPermissionsJson = requiredPermissionsJson;
        this.enabled = enabled;
    }

    public String getToolId() { return toolId; }
    public int getToolVersion() { return toolVersion; }
    public String getToolVersionId() { return toolVersionId; }
    public String getProtocol() { return protocol; }
    public String getRequiredPermissionsJson() { return requiredPermissionsJson; }
    public boolean isEnabled() { return enabled; }
}
