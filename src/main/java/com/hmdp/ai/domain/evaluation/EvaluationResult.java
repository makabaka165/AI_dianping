package com.hmdp.ai.domain.evaluation;

public final class EvaluationResult {
    private final String id,tenantId,workspaceId,evalRunId,evalCaseId,actualJson,metricsJson,errorCode,errorMessage,status;
    private final boolean passed;
    public EvaluationResult(String id,String tenantId,String workspaceId,String evalRunId,String evalCaseId,
                            String actualJson,String metricsJson,boolean passed,String errorCode,String errorMessage,
                            String status){this.id=id;this.tenantId=tenantId;this.workspaceId=workspaceId;
        this.evalRunId=evalRunId;this.evalCaseId=evalCaseId;this.actualJson=actualJson;this.metricsJson=metricsJson;
        this.passed=passed;this.errorCode=errorCode;this.errorMessage=errorMessage;this.status=status;}
    public String getId(){return id;} public String getTenantId(){return tenantId;}
    public String getWorkspaceId(){return workspaceId;} public String getEvalRunId(){return evalRunId;}
    public String getEvalCaseId(){return evalCaseId;} public String getActualJson(){return actualJson;}
    public String getMetricsJson(){return metricsJson;} public boolean isPassed(){return passed;}
    public String getErrorCode(){return errorCode;} public String getErrorMessage(){return errorMessage;}
    public String getStatus(){return status;}
}
