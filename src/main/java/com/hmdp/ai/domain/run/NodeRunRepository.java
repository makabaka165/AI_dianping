package com.hmdp.ai.domain.run;

public interface NodeRunRepository {
    NodeRunClaim start(ExecutionContext context, String nodeId, String nodeType, String inputJson,
                       String idempotencyKey);

    void complete(String tenantId, String workspaceId, String nodeRunId, String outputJson, String usageJson);

    void waitForResume(String tenantId, String workspaceId, String nodeRunId, String outputJson);

    void fail(String tenantId, String workspaceId, String nodeRunId, NodeRunStatus status,
              String errorCode, String errorMessage, boolean retryable);
}
