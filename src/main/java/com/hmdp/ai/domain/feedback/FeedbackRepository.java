package com.hmdp.ai.domain.feedback;

public interface FeedbackRepository {
    FeedbackRecord create(FeedbackRecord feedback);
    boolean nodeBelongsToRun(String tenantId,String workspaceId,String nodeRunId,String runId);
}
