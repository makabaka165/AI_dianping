package com.hmdp.ai.domain.knowledge;

public final class KnowledgeDocument {
    private final String id,tenantId,workspaceId,knowledgeBaseId,code,title,sourceType,status;private final int currentVersion;
    public KnowledgeDocument(String id,String tenantId,String workspaceId,String knowledgeBaseId,String code,String title,String sourceType,int currentVersion,String status){this.id=id;this.tenantId=tenantId;this.workspaceId=workspaceId;this.knowledgeBaseId=knowledgeBaseId;this.code=code;this.title=title;this.sourceType=sourceType;this.currentVersion=currentVersion;this.status=status;}
    public String getId(){return id;}public String getTenantId(){return tenantId;}public String getWorkspaceId(){return workspaceId;}public String getKnowledgeBaseId(){return knowledgeBaseId;}public String getCode(){return code;}public String getTitle(){return title;}public String getSourceType(){return sourceType;}public int getCurrentVersion(){return currentVersion;}public String getStatus(){return status;}
}
