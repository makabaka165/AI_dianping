package com.hmdp.ai.application.agent;

public interface AgentRuntime {
    void enqueue(String tenantId, String workspaceId, String runId);

    void recover();
}
