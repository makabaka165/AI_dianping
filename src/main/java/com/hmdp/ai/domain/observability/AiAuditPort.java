package com.hmdp.ai.domain.observability;
public interface AiAuditPort {void record(String tenantId,String workspaceId,String userId,String runId,String resourceType,
                                          String resourceId,String action,String summary,String status,String errorCode);}
