package com.hmdp.ai.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.artifact.ArtifactReference;
import com.hmdp.ai.domain.artifact.Citation;
import com.hmdp.ai.domain.run.UsageSummary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ToolResult {
    private final ToolCallStatus status;
    private final JsonNode data;
    private final List<Citation> citations;
    private final List<ArtifactReference> artifacts;
    private final List<String> warnings;
    private final UsageSummary usage;
    private final String errorCode;
    private final String errorMessage;
    private final boolean retryable;
    private final ToolAuditDetails auditDetails;

    public ToolResult(ToolCallStatus status, JsonNode data, List<Citation> citations,
                      List<ArtifactReference> artifacts, List<String> warnings, UsageSummary usage,
                      String errorCode, String errorMessage, boolean retryable) {
        this(status, data, citations, artifacts, warnings, usage, errorCode, errorMessage, retryable, null);
    }

    private ToolResult(ToolCallStatus status, JsonNode data, List<Citation> citations,
                       List<ArtifactReference> artifacts, List<String> warnings, UsageSummary usage,
                       String errorCode, String errorMessage, boolean retryable,
                       ToolAuditDetails auditDetails) {
        this.status = status;
        this.data = data;
        this.citations = immutable(citations);
        this.artifacts = immutable(artifacts);
        this.warnings = immutable(warnings);
        this.usage = usage;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.retryable = retryable;
        this.auditDetails = auditDetails;
    }

    public static ToolResult success(JsonNode data, long latencyMs) {
        return success(data, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                UsageSummary.empty(latencyMs));
    }

    public static ToolResult success(JsonNode data, List<Citation> citations,
                                     List<ArtifactReference> artifacts, List<String> warnings,
                                     UsageSummary usage) {
        return new ToolResult(ToolCallStatus.SUCCEEDED, data, citations, artifacts, warnings, usage,
                null, null, false);
    }

    public static ToolResult failure(ToolCallStatus status, String errorCode, String errorMessage,
                                     boolean retryable) {
        return new ToolResult(status, null, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), UsageSummary.empty(0), errorCode, errorMessage, retryable);
    }

    public ToolResult withAuditDetails(ToolAuditDetails details) {
        return new ToolResult(status, data, citations, artifacts, warnings, usage, errorCode, errorMessage,
                retryable, details);
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null
                ? Collections.emptyList() : values));
    }

    public ToolCallStatus getStatus() { return status; }
    public JsonNode getData() { return data; }
    public List<Citation> getCitations() { return citations; }
    public List<ArtifactReference> getArtifacts() { return artifacts; }
    public List<String> getWarnings() { return warnings; }
    public UsageSummary getUsage() { return usage; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isRetryable() { return retryable; }
    public ToolAuditDetails getAuditDetails() { return auditDetails; }
}
