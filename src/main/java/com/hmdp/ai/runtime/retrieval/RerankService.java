package com.hmdp.ai.runtime.retrieval;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;import java.util.List;
public interface RerankService {RerankOutcome rerank(String tenantId,String workspaceId,String modelProfileId,String query,List<KnowledgeChunk> chunks,List<Double> fallbackScores);}
