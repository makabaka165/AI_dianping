package com.hmdp.ai.domain.agent;

import java.util.List;
import java.util.Optional;

public interface AgentRepository {
    AgentDefinition create(AgentDefinition agent, String actorId);

    Optional<AgentDefinition> findById(String tenantId, String workspaceId, String agentIdOrCode);

    List<AgentDefinition> findPage(String tenantId, String workspaceId, int offset, int limit);

    List<AgentDefinition> findRunnablePage(String tenantId, String workspaceId, int offset, int limit);

    long count(String tenantId, String workspaceId);

    long countRunnable(String tenantId, String workspaceId);

    Optional<Integer> findPublishedVersion(String tenantId, String workspaceId, String agentId);

    int lockAndNextVersion(String tenantId, String workspaceId, String agentId);

    AgentVersion createVersion(AgentVersion version, List<String> toolVersionIds,
                               List<String> knowledgeBaseVersionIds, String actorId);

    Optional<AgentVersion> findVersion(String tenantId, String workspaceId, String agentId, int version);

    List<AgentVersion> findVersions(String tenantId, String workspaceId, String agentId, int offset, int limit);

    AgentVersion publish(String tenantId, String workspaceId, String agentId, int version, String actorId);

    Optional<PublishedAgentDefinition> loadPublished(String tenantId, String workspaceId,
                                                     String agentIdOrCode, int version);

    List<AgentToolBinding> findToolBindings(String tenantId, String workspaceId, String agentVersionId);

    List<AgentKnowledgeBinding> findKnowledgeBindings(String tenantId, String workspaceId, String agentVersionId);
}
