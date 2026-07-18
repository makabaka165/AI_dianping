package com.hmdp.ai.domain.memory;

import java.time.Duration;

public interface WorkingMemoryPort {
    void put(String tenantId,String workspaceId,String runId,String snapshotJson,Duration ttl);
    void delete(String tenantId,String workspaceId,String runId);
}
