package com.hmdp.ai.domain.tool;

import java.time.Duration;

public interface ToolBudgetPort {
    boolean reserve(String tenantId, String workspaceId, String runId, int maximumCalls, Duration ttl);
}
