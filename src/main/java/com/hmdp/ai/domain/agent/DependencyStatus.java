package com.hmdp.ai.domain.agent;

public final class DependencyStatus {
    private final String id;
    private final boolean exists;
    private final String status;
    private final boolean enabled;
    private final String secondaryStatus;
    private final String metadataJson;

    public DependencyStatus(String id, boolean exists, String status, boolean enabled,
                            String secondaryStatus, String metadataJson) {
        this.id = id;
        this.exists = exists;
        this.status = status;
        this.enabled = enabled;
        this.secondaryStatus = secondaryStatus;
        this.metadataJson = metadataJson;
    }

    public String getId() { return id; }
    public boolean isExists() { return exists; }
    public String getStatus() { return status; }
    public boolean isEnabled() { return enabled; }
    public String getSecondaryStatus() { return secondaryStatus; }
    public String getMetadataJson() { return metadataJson; }
}
