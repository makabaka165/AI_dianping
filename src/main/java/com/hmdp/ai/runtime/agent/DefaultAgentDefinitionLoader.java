package com.hmdp.ai.runtime.agent;

import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.agent.AgentRepository;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class DefaultAgentDefinitionLoader implements AgentDefinitionLoader {
    private final AgentRepository repository;

    public DefaultAgentDefinitionLoader(AgentRepository repository) {
        this.repository = repository;
    }

    @Override
    public PublishedAgentDefinition load(String tenantId, String workspaceId, String agentId, int version) {
        return repository.loadPublished(tenantId, workspaceId, agentId, version)
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_VERSION_NOT_FOUND,
                        "published agent version not found"));
    }
}
