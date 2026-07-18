package com.hmdp.ai.domain.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WorkflowWaitEventPayload {
    private final String runId;
    private final RunStatus status;
    private final String nodeId;
    private final String resumeToken;
    private final Instant expiresAt;
    private final List<String> questions;

    public WorkflowWaitEventPayload(String runId, RunStatus status, String nodeId, String resumeToken,
                                    Instant expiresAt, List<String> questions) {
        this.runId = runId;
        this.status = status;
        this.nodeId = nodeId;
        this.resumeToken = resumeToken;
        this.expiresAt = expiresAt;
        this.questions = Collections.unmodifiableList(new ArrayList<>(questions));
    }

    public String getRunId() { return runId; }
    public RunStatus getStatus() { return status; }
    public String getNodeId() { return nodeId; }
    public String getResumeToken() { return resumeToken; }
    public Instant getExpiresAt() { return expiresAt; }
    public List<String> getQuestions() { return questions; }
}
