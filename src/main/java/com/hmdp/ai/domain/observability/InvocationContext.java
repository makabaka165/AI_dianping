package com.hmdp.ai.domain.observability;

import com.hmdp.ai.domain.run.ExecutionContext;

import java.util.Objects;

public final class InvocationContext {
    private final String tenantId;
    private final String workspaceId;
    private final String runId;
    private final String nodeRunId;
    private final String invocationId;
    private final String traceId;
    private final String agentId;
    private final int agentVersion;
    private final String userId;

    public InvocationContext(String tenantId, String workspaceId, String runId, String nodeRunId,
                             String invocationId, String traceId, String agentId, int agentVersion,
                             String userId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.nodeRunId = Objects.requireNonNull(nodeRunId, "nodeRunId");
        this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.agentVersion = agentVersion;
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public static InvocationContext from(ExecutionContext context, String nodeRunId, String invocationId) {
        return new InvocationContext(context.getTenantId(), context.getWorkspaceId(), context.getRunId(),
                nodeRunId, invocationId, context.getTraceId(), context.getAgentId(), context.getAgentVersion(),
                context.getUserId());
    }

    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getRunId() { return runId; }
    public String getNodeRunId() { return nodeRunId; }
    public String getInvocationId() { return invocationId; }
    public String getTraceId() { return traceId; }
    public String getAgentId() { return agentId; }
    public int getAgentVersion() { return agentVersion; }
    public String getUserId() { return userId; }
}
