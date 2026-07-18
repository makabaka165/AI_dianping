package com.hmdp.ai.application.dto.memory;
import com.hmdp.ai.domain.memory.MemoryFact;import java.time.Instant;
public final class MemoryFactResponse {private final String id,factType,factValue,sourceRunId,sensitivityLevel,status;
    private final double confidence;private final boolean confirmedByUser;private final Instant expiresAt,createdAt,updatedAt;
    public MemoryFactResponse(MemoryFact f){id=f.getId();factType=f.getFactType();factValue=f.getFactValue();
        sourceRunId=f.getSourceRunId();sensitivityLevel=f.getSensitivityLevel();status=f.getStatus().name();
        confidence=f.getConfidence();confirmedByUser=f.isConfirmedByUser();expiresAt=f.getExpiresAt();
        createdAt=f.getCreatedAt();updatedAt=f.getUpdatedAt();}
    public String getId(){return id;}public String getFactType(){return factType;}public String getFactValue(){return factValue;}
    public String getSourceRunId(){return sourceRunId;}public String getSensitivityLevel(){return sensitivityLevel;}
    public String getStatus(){return status;}public double getConfidence(){return confidence;}
    public boolean isConfirmedByUser(){return confirmedByUser;}public Instant getExpiresAt(){return expiresAt;}
    public Instant getCreatedAt(){return createdAt;}public Instant getUpdatedAt(){return updatedAt;}}
