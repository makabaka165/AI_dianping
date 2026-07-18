package com.hmdp.ai.domain.model;

public final class ModelHealthResult {
    private final String modelProfileId;
    private final String status;
    private final long latencyMs;
    private final String errorCode;

    public ModelHealthResult(String modelProfileId, String status, long latencyMs, String errorCode) {
        this.modelProfileId = modelProfileId;
        this.status = status;
        this.latencyMs = latencyMs;
        this.errorCode = errorCode;
    }

    public String getModelProfileId() { return modelProfileId; }
    public String getStatus() { return status; }
    public long getLatencyMs() { return latencyMs; }
    public String getErrorCode() { return errorCode; }
}
