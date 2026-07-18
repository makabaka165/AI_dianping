package com.hmdp.ai.domain.knowledge;

/**
 * Stable seam for ACL-scoped knowledge retrieval.
 * Implementations must apply tenant, workspace, knowledge-base and user ACL
 * filters before either vector or lexical recall.
 */
public interface KnowledgeRetriever {

    HybridRetrievalResult retrieve(String tenantId,
                                   String workspaceId,
                                   String userId,
                                   String knowledgeBaseId,
                                   Integer knowledgeBaseVersion,
                                   String query,
                                   Integer topK);
}
