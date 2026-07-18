package com.hmdp.ai.domain.knowledge;

public final class KnowledgeBaseVersion {
    private final String id,tenantId,workspaceId,knowledgeBaseId,embeddingModelProfileId,chunkingPolicyJson,retrievalPolicyJson,indexVersion,indexStatus,status;
    private final int version,embeddingDimension;
    public KnowledgeBaseVersion(String id,String tenantId,String workspaceId,String knowledgeBaseId,int version,String embeddingModelProfileId,int embeddingDimension,String chunkingPolicyJson,String retrievalPolicyJson,String indexVersion,String indexStatus,String status){this.id=id;this.tenantId=tenantId;this.workspaceId=workspaceId;this.knowledgeBaseId=knowledgeBaseId;this.version=version;this.embeddingModelProfileId=embeddingModelProfileId;this.embeddingDimension=embeddingDimension;this.chunkingPolicyJson=chunkingPolicyJson;this.retrievalPolicyJson=retrievalPolicyJson;this.indexVersion=indexVersion;this.indexStatus=indexStatus;this.status=status;}
    public String getId(){return id;}public String getTenantId(){return tenantId;}public String getWorkspaceId(){return workspaceId;}public String getKnowledgeBaseId(){return knowledgeBaseId;}public int getVersion(){return version;}public String getEmbeddingModelProfileId(){return embeddingModelProfileId;}public int getEmbeddingDimension(){return embeddingDimension;}public String getChunkingPolicyJson(){return chunkingPolicyJson;}public String getRetrievalPolicyJson(){return retrievalPolicyJson;}public String getIndexVersion(){return indexVersion;}public String getIndexStatus(){return indexStatus;}public String getStatus(){return status;}
}
