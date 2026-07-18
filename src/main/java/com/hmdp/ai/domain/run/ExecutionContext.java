package com.hmdp.ai.domain.run;

import com.hmdp.ai.domain.security.AuthorizationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExecutionContext {
    private final String tenantId;
    private final String workspaceId;
    private final String userId;
    private final String sessionId;
    private final String conversationId;
    private final String runId;
    private final String agentId;
    private final int agentVersion;
    private final String locale;
    private final String timezone;
    private final List<AttachmentReference> attachments;
    private final List<String> referenceUris;
    private final AuthorizationContext authorizationContext;
    private final ExecutionBudget executionBudget;
    private final Instant deadline;
    private final Map<String, Object> variables;
    private final String traceId;

    public ExecutionContext(String tenantId, String workspaceId, String userId, String sessionId,
                            String conversationId, String runId, String agentId, int agentVersion,
                            String locale, String timezone, List<AttachmentReference> attachments,
                            List<String> referenceUris, AuthorizationContext authorizationContext,
                            ExecutionBudget executionBudget, Instant deadline, Map<String, Object> variables,
                            String traceId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.conversationId = conversationId;
        this.runId = Objects.requireNonNull(runId, "runId");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.agentVersion = agentVersion;
        this.locale = locale == null ? "zh-CN" : locale;
        this.timezone = timezone == null ? "Asia/Shanghai" : timezone;
        this.attachments = Collections.unmodifiableList(new ArrayList<>(attachments == null
                ? Collections.emptyList() : attachments));
        this.referenceUris = Collections.unmodifiableList(new ArrayList<>(referenceUris == null
                ? Collections.emptyList() : referenceUris));
        this.authorizationContext = Objects.requireNonNull(authorizationContext, "authorizationContext");
        this.executionBudget = Objects.requireNonNull(executionBudget, "executionBudget");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables == null
                ? Collections.emptyMap() : variables));
        this.traceId = Objects.requireNonNull(traceId, "traceId");
    }

    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public String getConversationId() { return conversationId; }
    public String getRunId() { return runId; }
    public String getAgentId() { return agentId; }
    public int getAgentVersion() { return agentVersion; }
    public String getLocale() { return locale; }
    public String getTimezone() { return timezone; }
    public List<AttachmentReference> getAttachments() { return attachments; }
    public List<String> getReferenceUris() { return referenceUris; }
    public AuthorizationContext getAuthorizationContext() { return authorizationContext; }
    public ExecutionBudget getExecutionBudget() { return executionBudget; }
    public Instant getDeadline() { return deadline; }
    public Map<String, Object> getVariables() { return variables; }
    public String getTraceId() { return traceId; }
}
