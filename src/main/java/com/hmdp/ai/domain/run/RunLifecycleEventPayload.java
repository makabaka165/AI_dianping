package com.hmdp.ai.domain.run;


public final class RunLifecycleEventPayload {
    private final String runId;
    private final RunStatus status;
    private final String nodeId;
    private final String errorCode;
    private final String summary;

    public RunLifecycleEventPayload(String runId, RunStatus status, String nodeId,
                                    String errorCode, String summary) {
        this.runId = runId;
        this.status = status;
        this.nodeId = nodeId;
        this.errorCode = errorCode;
        this.summary = summary;
    }

    public String getRunId() { return runId; }
    public RunStatus getStatus() { return status; }
    public String getNodeId() { return nodeId; }
    public String getErrorCode() { return errorCode; }
    public String getSummary() { return summary; }
}
