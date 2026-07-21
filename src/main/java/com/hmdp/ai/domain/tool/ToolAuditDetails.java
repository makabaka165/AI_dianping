package com.hmdp.ai.domain.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ToolAuditDetails {
    private final String inputSchemaValidationResult;
    private final String approvalRequestId;
    private final int retryCount;
    private final String circuitBreakerState;
    private final int timeoutMs;
    private final long resultSizeBytes;
    private final List<String> artifactIds;
    private final List<String> citationIds;

    public ToolAuditDetails(String inputSchemaValidationResult, String approvalRequestId, int retryCount,
                            String circuitBreakerState, int timeoutMs, long resultSizeBytes,
                            List<String> artifactIds, List<String> citationIds) {
        this.inputSchemaValidationResult = inputSchemaValidationResult;
        this.approvalRequestId = approvalRequestId;
        this.retryCount = retryCount;
        this.circuitBreakerState = circuitBreakerState;
        this.timeoutMs = timeoutMs;
        this.resultSizeBytes = resultSizeBytes;
        this.artifactIds = immutable(artifactIds);
        this.citationIds = immutable(citationIds);
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null
                ? Collections.emptyList() : values));
    }

    public String getInputSchemaValidationResult() { return inputSchemaValidationResult; }
    public String getApprovalRequestId() { return approvalRequestId; }
    public int getRetryCount() { return retryCount; }
    public String getCircuitBreakerState() { return circuitBreakerState; }
    public int getTimeoutMs() { return timeoutMs; }
    public long getResultSizeBytes() { return resultSizeBytes; }
    public List<String> getArtifactIds() { return artifactIds; }
    public List<String> getCitationIds() { return citationIds; }
}
