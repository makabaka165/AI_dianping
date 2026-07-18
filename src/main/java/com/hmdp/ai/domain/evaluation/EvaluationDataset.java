package com.hmdp.ai.domain.evaluation;

public final class EvaluationDataset {
    private final String id,tenantId,workspaceId,code,name,description,status; private final EvaluationType type;
    public EvaluationDataset(String id,String tenantId,String workspaceId,String code,String name,String description,
                             EvaluationType type,String status){this.id=id;this.tenantId=tenantId;this.workspaceId=workspaceId;
        this.code=code;this.name=name;this.description=description;this.type=type;this.status=status;}
    public String getId(){return id;} public String getTenantId(){return tenantId;}
    public String getWorkspaceId(){return workspaceId;} public String getCode(){return code;}
    public String getName(){return name;} public String getDescription(){return description;}
    public EvaluationType getType(){return type;} public String getStatus(){return status;}
}
