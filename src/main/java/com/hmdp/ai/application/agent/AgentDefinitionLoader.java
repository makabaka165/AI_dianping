package com.hmdp.ai.application.agent;

import com.hmdp.ai.domain.agent.PublishedAgentDefinition;

public interface AgentDefinitionLoader {
    PublishedAgentDefinition load(String tenantId, String workspaceId, String agentId, int version);
}
