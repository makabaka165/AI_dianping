package com.hmdp.ai.domain.memory;

import java.time.Instant;

public final class ConversationRecord {
    private final String id, tenantId, workspaceId, userId, sessionId, agentId, title, status;
    private final int agentVersion;
    private final Instant lastMessageAt, createdAt;

    public ConversationRecord(String id, String tenantId, String workspaceId, String userId, String sessionId,
                              String agentId, int agentVersion, String title, String status,
                              Instant lastMessageAt, Instant createdAt) {
        this.id=id;this.tenantId=tenantId;this.workspaceId=workspaceId;this.userId=userId;this.sessionId=sessionId;
        this.agentId=agentId;this.agentVersion=agentVersion;this.title=title;this.status=status;
        this.lastMessageAt=lastMessageAt;this.createdAt=createdAt;
    }
    public String getId(){return id;} public String getTenantId(){return tenantId;}
    public String getWorkspaceId(){return workspaceId;} public String getUserId(){return userId;}
    public String getSessionId(){return sessionId;} public String getAgentId(){return agentId;}
    public int getAgentVersion(){return agentVersion;} public String getTitle(){return title;}
    public String getStatus(){return status;} public Instant getLastMessageAt(){return lastMessageAt;}
    public Instant getCreatedAt(){return createdAt;}
}
