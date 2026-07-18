package com.hmdp.ai.domain.workflow;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public interface WorkflowStateRepository {
    Optional<WorkflowState> find(String tenantId, String workspaceId, String runId);

    WorkflowState create(WorkflowState initialState, String actorId);

    WorkflowState saveProgress(WorkflowState state, String actorId);

    WorkflowState saveWaiting(WorkflowState state, WorkflowStateStatus waitingStatus,
                              String waitingNodeCode, String resumeTokenHash, Instant expiresAt,
                              String actorId);

    boolean resume(String tenantId, String workspaceId, String runId, String resumeTokenHash,
                   Map<String, Object> resumeVariables, String actorId);

    void complete(String tenantId, String workspaceId, String runId, String actorId);

    void fail(String tenantId, String workspaceId, String runId, String actorId);
}
