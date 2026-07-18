package com.hmdp.ai.application.dto.model;

import com.hmdp.ai.domain.model.ModelHealthResult;

public final class ModelHealthResponse {
    private final String modelProfileId;
    private final String status;
    private final long latencyMs;
    private final String errorCode;

    public ModelHealthResponse(String modelProfileId, String status, long latencyMs, String errorCode) {
        this.modelProfileId = modelProfileId;
        this.status = status;
        this.latencyMs = latencyMs;
        this.errorCode = errorCode;
    }

    public ModelHealthResponse(ModelHealthResult result) {
        this(result.getModelProfileId(), result.getStatus(), result.getLatencyMs(), result.getErrorCode());
    }

    public String getModelProfileId() { return modelProfileId; }
    public String getStatus() { return status; }
    public long getLatencyMs() { return latencyMs; }
    public String getErrorCode() { return errorCode; }
}
