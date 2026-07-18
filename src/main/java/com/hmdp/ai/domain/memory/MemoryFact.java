package com.hmdp.ai.domain.memory;

import java.time.Instant;

public final class MemoryFact {
    private final String id, tenantId, workspaceId, userId, factType, factValue, sourceMessageId,
            sourceRunId, sensitivityLevel;
    private final double confidence;
    private final boolean confirmedByUser;
    private final Instant expiresAt, createdAt, updatedAt;
    private final MemoryFactStatus status;
    public MemoryFact(String id,String tenantId,String workspaceId,String userId,String factType,String factValue,
                      String sourceMessageId,String sourceRunId,double confidence,boolean confirmedByUser,
                      String sensitivityLevel,Instant expiresAt,MemoryFactStatus status,Instant createdAt,
                      Instant updatedAt){this.id=id;this.tenantId=tenantId;this.workspaceId=workspaceId;this.userId=userId;
        this.factType=factType;this.factValue=factValue;this.sourceMessageId=sourceMessageId;this.sourceRunId=sourceRunId;
        this.confidence=confidence;this.confirmedByUser=confirmedByUser;this.sensitivityLevel=sensitivityLevel;
        this.expiresAt=expiresAt;this.status=status;this.createdAt=createdAt;this.updatedAt=updatedAt;}
    public String getId(){return id;} public String getTenantId(){return tenantId;}
    public String getWorkspaceId(){return workspaceId;} public String getUserId(){return userId;}
    public String getFactType(){return factType;} public String getFactValue(){return factValue;}
    public String getSourceMessageId(){return sourceMessageId;} public String getSourceRunId(){return sourceRunId;}
    public double getConfidence(){return confidence;} public boolean isConfirmedByUser(){return confirmedByUser;}
    public String getSensitivityLevel(){return sensitivityLevel;} public Instant getExpiresAt(){return expiresAt;}
    public MemoryFactStatus getStatus(){return status;} public Instant getCreatedAt(){return createdAt;}
    public Instant getUpdatedAt(){return updatedAt;}
}
