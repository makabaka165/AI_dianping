package com.hmdp.ai.domain.tool;
import java.util.Optional;
public interface ToolDefinitionRepository {Optional<ToolDefinition> findBound(String tenantId,String workspaceId,String agentId,int agentVersion,String toolCode,int toolVersion);}
