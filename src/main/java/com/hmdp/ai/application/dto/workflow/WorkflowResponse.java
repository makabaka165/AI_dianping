package com.hmdp.ai.application.dto.workflow;

import com.hmdp.ai.domain.workflow.WorkflowCatalogEntry;

public final class WorkflowResponse {
    private final String id;
    private final String code;
    private final String name;
    private final String description;
    private final int latestVersion;
    private final String status;

    public WorkflowResponse(WorkflowCatalogEntry workflow) {
        this.id = workflow.getId(); this.code = workflow.getCode(); this.name = workflow.getName();
        this.description = workflow.getDescription(); this.latestVersion = workflow.getLatestVersion();
        this.status = workflow.getStatus();
    }
    public String getId(){return id;} public String getCode(){return code;} public String getName(){return name;}
    public String getDescription(){return description;} public int getLatestVersion(){return latestVersion;}
    public String getStatus(){return status;}
}
