package com.hmdp.ai.runtime.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hmdp.ai.runtime")
public class AgentRuntimeProperties {
    private int coreThreads = 2;
    private int maxThreads = 8;
    private int queueCapacity = 200;
    private int recoveryBatchSize = 100;
    private boolean recoveryEnabled = true;

    public int getCoreThreads() { return coreThreads; }
    public void setCoreThreads(int coreThreads) { this.coreThreads = coreThreads; }
    public int getMaxThreads() { return maxThreads; }
    public void setMaxThreads(int maxThreads) { this.maxThreads = maxThreads; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getRecoveryBatchSize() { return recoveryBatchSize; }
    public void setRecoveryBatchSize(int recoveryBatchSize) { this.recoveryBatchSize = recoveryBatchSize; }
    public boolean isRecoveryEnabled() { return recoveryEnabled; }
    public void setRecoveryEnabled(boolean recoveryEnabled) { this.recoveryEnabled = recoveryEnabled; }
}
