package com.hmdp.ai.domain.evaluation;

import java.time.Instant;

public final class EvaluationRun {
    private final String id,tenantId,workspaceId,datasetId,targetType,targetId,status,summaryJson;
    private final Integer targetVersion; private final Instant startedAt,finishedAt;
    public EvaluationRun(String id,String tenantId,String workspaceId,String datasetId,String targetType,String targetId,
                         Integer targetVersion,String status,String summaryJson,Instant startedAt,Instant finishedAt){
        this.id=id;this.tenantId=tenantId;this.workspaceId=workspaceId;this.datasetId=datasetId;
        this.targetType=targetType;this.targetId=targetId;this.targetVersion=targetVersion;this.status=status;
        this.summaryJson=summaryJson;this.startedAt=startedAt;this.finishedAt=finishedAt;}
    public String getId(){return id;} public String getTenantId(){return tenantId;}
    public String getWorkspaceId(){return workspaceId;} public String getDatasetId(){return datasetId;}
    public String getTargetType(){return targetType;} public String getTargetId(){return targetId;}
    public Integer getTargetVersion(){return targetVersion;} public String getStatus(){return status;}
    public String getSummaryJson(){return summaryJson;} public Instant getStartedAt(){return startedAt;}
    public Instant getFinishedAt(){return finishedAt;}
}
