package com.hmdp.ai.domain.knowledge;

/**
 * Stable seam for ACL-scoped knowledge retrieval.
 * Implementations must apply tenant, workspace, knowledge-base and user ACL
 * filters before either vector or lexical recall.
 */
public interface KnowledgeRetriever {

    default HybridRetrievalResult retrieve(KnowledgeRetrievalRequest request) {
        return retrieve(request.getTenantId(), request.getWorkspaceId(), request.getUserId(),
                request.getKnowledgeBaseId(), request.getKnowledgeBaseVersion(), request.getQuery(),
                request.getTopK());
    }

    HybridRetrievalResult retrieve(String tenantId,
                                   String workspaceId,
                                   String userId,
                                   String knowledgeBaseId,
                                   Integer knowledgeBaseVersion,
                                   String query,
                                   Integer topK);
}
