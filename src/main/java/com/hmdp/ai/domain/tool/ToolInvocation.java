package com.hmdp.ai.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.ai.domain.run.ExecutionContext;

public final class ToolInvocation {
    private final String callId;
    private final String toolCode;
    private final int toolVersion;
    private final ExecutionContext context;
    private final JsonNode input;
    private final String idempotencyKey;
    private final boolean approved;
    private final String nodeRunId;
    private final String approvalRequestId;

    public ToolInvocation(String callId, String toolCode, int toolVersion, ExecutionContext context,
                          JsonNode input) {
        this(callId, toolCode, toolVersion, context, input, callId, false);
    }

    public ToolInvocation(String callId, String toolCode, int toolVersion, ExecutionContext context,
                          JsonNode input, String idempotencyKey, boolean approved) {
        this(callId, toolCode, toolVersion, context, input, idempotencyKey, approved,
                context.getRunId() + ":tool", null);
    }

    public ToolInvocation(String callId, String toolCode, int toolVersion, ExecutionContext context,
                          JsonNode input, String idempotencyKey, boolean approved, String nodeRunId,
                          String approvalRequestId) {
        this.callId = callId;
        this.toolCode = toolCode;
        this.toolVersion = toolVersion;
        this.context = context;
        this.input = input;
        this.idempotencyKey = idempotencyKey;
        this.approved = approved;
        this.nodeRunId = nodeRunId;
        this.approvalRequestId = approvalRequestId;
    }

    public String getCallId() { return callId; }
    public String getToolCode() { return toolCode; }
    public int getToolVersion() { return toolVersion; }
    public ExecutionContext getContext() { return context; }
    public JsonNode getInput() { return input; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public boolean isApproved() { return approved; }
    public String getNodeRunId() { return nodeRunId; }
    public String getApprovalRequestId() { return approvalRequestId; }
}
