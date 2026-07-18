package com.hmdp.ai.domain.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Durable workflow continuation state. MySQL is the source of truth for every resumable run. */
public final class WorkflowState {
    private final String tenantId;
    private final String workspaceId;
    private final String runId;
    private final String workflowVersionId;
    private final List<String> currentNodeCodes;
    private final Map<String, Object> variables;
    private final Set<String> completedNodeKeys;
    private final Map<String, Integer> executionCounts;
    private final String waitingNodeCode;
    private final WorkflowStateStatus status;
    private final Instant expiresAt;
    private final long stateVersion;

    public WorkflowState(String tenantId, String workspaceId, String runId, String workflowVersionId,
                         List<String> currentNodeCodes, Map<String, Object> variables,
                         Set<String> completedNodeKeys, Map<String, Integer> executionCounts,
                         String waitingNodeCode, WorkflowStateStatus status, Instant expiresAt,
                         long stateVersion) {
        this.tenantId = tenantId;
        this.workspaceId = workspaceId;
        this.runId = runId;
        this.workflowVersionId = workflowVersionId;
        this.currentNodeCodes = Collections.unmodifiableList(new ArrayList<>(currentNodeCodes));
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        this.completedNodeKeys = Collections.unmodifiableSet(new LinkedHashSet<>(completedNodeKeys));
        this.executionCounts = Collections.unmodifiableMap(new LinkedHashMap<>(executionCounts));
        this.waitingNodeCode = waitingNodeCode;
        this.status = status;
        this.expiresAt = expiresAt;
        this.stateVersion = stateVersion;
    }

    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getRunId() { return runId; }
    public String getWorkflowVersionId() { return workflowVersionId; }
    public List<String> getCurrentNodeCodes() { return currentNodeCodes; }
    public Map<String, Object> getVariables() { return variables; }
    public Set<String> getCompletedNodeKeys() { return completedNodeKeys; }
    public Map<String, Integer> getExecutionCounts() { return executionCounts; }
    public String getWaitingNodeCode() { return waitingNodeCode; }
    public WorkflowStateStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getStateVersion() { return stateVersion; }

    public WorkflowState progress(List<String> nodes, Map<String, Object> nextVariables,
                                  Set<String> completed, Map<String, Integer> counts) {
        return new WorkflowState(tenantId, workspaceId, runId, workflowVersionId, nodes, nextVariables,
                completed, counts, null, WorkflowStateStatus.RUNNING, null, stateVersion);
    }
}
