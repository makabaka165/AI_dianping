package com.hmdp.ai.domain.evaluation;

public final class EvaluationCase {
    private final String id,tenantId,workspaceId,datasetId,name,inputJson,expectedJson,assertionsJson,status;
    public EvaluationCase(String id,String tenantId,String workspaceId,String datasetId,String name,String inputJson,
                          String expectedJson,String assertionsJson,String status){this.id=id;this.tenantId=tenantId;
        this.workspaceId=workspaceId;this.datasetId=datasetId;this.name=name;this.inputJson=inputJson;
        this.expectedJson=expectedJson;this.assertionsJson=assertionsJson;this.status=status;}
    public String getId(){return id;} public String getTenantId(){return tenantId;}
    public String getWorkspaceId(){return workspaceId;} public String getDatasetId(){return datasetId;}
    public String getName(){return name;} public String getInputJson(){return inputJson;}
    public String getExpectedJson(){return expectedJson;} public String getAssertionsJson(){return assertionsJson;}
    public String getStatus(){return status;}
}
