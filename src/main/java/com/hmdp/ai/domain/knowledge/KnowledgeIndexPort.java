package com.hmdp.ai.domain.knowledge;

import java.util.List;

public interface KnowledgeIndexPort {
    void ensureIndex(String indexVersion,int dimension);
    void index(List<KnowledgeChunk> chunks);
    void delete(String indexVersion,List<String> chunkIds);
    void drop(String indexVersion);
    IndexVerificationResult verify(KnowledgeSearchScope scope, List<KnowledgeChunk> expectedChunks);
    List<IndexHit> vectorSearch(KnowledgeSearchScope scope,float[] embedding,int limit);
    List<IndexHit> lexicalSearch(KnowledgeSearchScope scope,String normalizedQuery,int limit);
}
