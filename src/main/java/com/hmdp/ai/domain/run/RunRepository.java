package com.hmdp.ai.domain.run;

import java.util.List;
import java.util.Optional;

public interface RunRepository {
    AgentRunRecord create(AgentRunRecord run);

    Optional<AgentRunRecord> find(String tenantId, String workspaceId, String runId);

    boolean claimQueued(String tenantId, String workspaceId, String runId);

    void complete(String tenantId, String workspaceId, String runId, String outputJson);

    void fail(String tenantId, String workspaceId, String runId, String errorCode, String errorMessage,
              RunStatus terminalStatus);

    boolean cancel(String tenantId, String workspaceId, String runId, String actorId);

    boolean markWaiting(String tenantId, String workspaceId, String runId, RunStatus waitingStatus,
                        String resumeTokenHash, java.time.Instant expiresAt, String actorId);

    boolean resumeWaiting(String tenantId, String workspaceId, String runId, String resumeTokenHash,
                          String resumeDataJson, String actorId);

    long appendEvent(String tenantId, String workspaceId, String runId, String eventType, String payloadJson);

    List<RunEvent> findEvents(String tenantId, String workspaceId, String runId, long afterSequence, int limit);

    default long latestEventSequence(String tenantId, String workspaceId, String runId) {
        return findEvents(tenantId, workspaceId, runId, 0, Integer.MAX_VALUE).stream()
                .mapToLong(RunEvent::getSequence).max().orElse(0);
    }

    List<AgentRunRecord> findRecoverable(int limit);

    int requeueInterruptedRuns();
}
