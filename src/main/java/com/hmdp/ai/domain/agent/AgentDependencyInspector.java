package com.hmdp.ai.domain.agent;

import java.util.List;
import java.util.Optional;

public interface AgentDependencyInspector {
    Optional<DependencyStatus> workflow(String tenantId, String workspaceId, String workflowVersionId);

    List<DependencyStatus> tools(String tenantId, String workspaceId, String agentVersionId);

    List<DependencyStatus> knowledgeBases(String tenantId, String workspaceId, String agentVersionId);

    List<String> rawToolVersionIds(String tenantId, String workspaceId, String agentVersionId);

    List<String> rawKnowledgeVersionIds(String tenantId, String workspaceId, String agentVersionId);
}
