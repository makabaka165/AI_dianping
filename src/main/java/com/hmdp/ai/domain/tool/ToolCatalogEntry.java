package com.hmdp.ai.domain.tool;

public final class ToolCatalogEntry {
    private final String id; private final String tenantId; private final String workspaceId;
    private final String code; private final String name; private final String description;
    private final int latestVersion; private final String status;
    public ToolCatalogEntry(String id,String tenantId,String workspaceId,String code,String name,String description,int latestVersion,String status){this.id=id;this.tenantId=tenantId;this.workspaceId=workspaceId;this.code=code;this.name=name;this.description=description;this.latestVersion=latestVersion;this.status=status;}
    public String getId(){return id;} public String getTenantId(){return tenantId;} public String getWorkspaceId(){return workspaceId;}
    public String getCode(){return code;} public String getName(){return name;} public String getDescription(){return description;}
    public int getLatestVersion(){return latestVersion;} public String getStatus(){return status;}
}
