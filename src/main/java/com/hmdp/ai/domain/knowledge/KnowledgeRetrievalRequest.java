package com.hmdp.ai.domain.knowledge;

import com.hmdp.ai.domain.observability.InvocationContext;

import java.util.Objects;

/**
 * Complete input for one ACL-scoped hybrid retrieval operation.
 */
public final class KnowledgeRetrievalRequest {
    private final InvocationContext invocationContext;
    private final String tenantId;
    private final String workspaceId;
    private final String userId;
    private final String knowledgeBaseId;
    private final Integer knowledgeBaseVersion;
    private final String query;
    private final Integer topK;

    private KnowledgeRetrievalRequest(InvocationContext invocationContext, String tenantId,
                                      String workspaceId, String userId, String knowledgeBaseId,
                                      Integer knowledgeBaseVersion, String query, Integer topK) {
        this.invocationContext = invocationContext;
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        this.knowledgeBaseVersion = knowledgeBaseVersion;
        this.query = Objects.requireNonNull(query, "query");
        this.topK = topK;
    }

    public static KnowledgeRetrievalRequest forRun(InvocationContext context, String knowledgeBaseId,
                                                   Integer knowledgeBaseVersion, String query, Integer topK) {
        Objects.requireNonNull(context, "context");
        return new KnowledgeRetrievalRequest(context, context.getTenantId(), context.getWorkspaceId(),
                context.getUserId(), knowledgeBaseId, knowledgeBaseVersion, query, topK);
    }

    public static KnowledgeRetrievalRequest interactive(String tenantId, String workspaceId, String userId,
                                                        String knowledgeBaseId, Integer knowledgeBaseVersion,
                                                        String query, Integer topK) {
        return new KnowledgeRetrievalRequest(null, tenantId, workspaceId, userId, knowledgeBaseId,
                knowledgeBaseVersion, query, topK);
    }

    public InvocationContext getInvocationContext() { return invocationContext; }
    public String getTenantId() { return tenantId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getUserId() { return userId; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public Integer getKnowledgeBaseVersion() { return knowledgeBaseVersion; }
    public String getQuery() { return query; }
    public Integer getTopK() { return topK; }
}
