package com.hmdp.ai.domain.memory;

import java.time.Instant;

public final class MessageRecord {
    private final String id, tenantId, workspaceId, conversationId, runId, agentId, content,
            structuredContentJson, toolCallId, attachmentsJson, citationsJson, tokenUsageJson;
    private final int agentVersion;
    private final MessageRole role;
    private final Instant createdAt;

    public MessageRecord(String id,String tenantId,String workspaceId,String conversationId,String runId,
                         String agentId,int agentVersion,MessageRole role,String content,String structuredContentJson,
                         String toolCallId,String attachmentsJson,String citationsJson,String tokenUsageJson,
                         Instant createdAt){this.id=id;this.tenantId=tenantId;this.workspaceId=workspaceId;
        this.conversationId=conversationId;this.runId=runId;this.agentId=agentId;this.agentVersion=agentVersion;
        this.role=role;this.content=content;this.structuredContentJson=structuredContentJson;this.toolCallId=toolCallId;
        this.attachmentsJson=attachmentsJson;this.citationsJson=citationsJson;this.tokenUsageJson=tokenUsageJson;
        this.createdAt=createdAt;}
    public String getId(){return id;} public String getTenantId(){return tenantId;}
    public String getWorkspaceId(){return workspaceId;} public String getConversationId(){return conversationId;}
    public String getRunId(){return runId;} public String getAgentId(){return agentId;}
    public int getAgentVersion(){return agentVersion;} public MessageRole getRole(){return role;}
    public String getContent(){return content;} public String getStructuredContentJson(){return structuredContentJson;}
    public String getToolCallId(){return toolCallId;} public String getAttachmentsJson(){return attachmentsJson;}
    public String getCitationsJson(){return citationsJson;} public String getTokenUsageJson(){return tokenUsageJson;}
    public Instant getCreatedAt(){return createdAt;}
}
