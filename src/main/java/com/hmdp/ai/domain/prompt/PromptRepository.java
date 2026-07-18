package com.hmdp.ai.domain.prompt;

import java.util.List;
import java.util.Optional;

public interface PromptRepository {
    PromptDefinition create(PromptDefinition prompt, String actorId);

    Optional<PromptDefinition> findById(String tenantId, String workspaceId, String promptId);

    List<PromptDefinition> findPage(String tenantId, String workspaceId, int offset, int limit);

    long count(String tenantId, String workspaceId);

    int lockAndNextVersion(String tenantId, String workspaceId, String promptId);

    PromptVersion createVersion(PromptVersion version, String actorId);

    Optional<PromptVersion> findVersion(String tenantId, String workspaceId, String promptId, int version);

    Optional<PromptVersion> findVersionById(String tenantId, String workspaceId, String versionId);

    List<PromptVersion> findVersions(String tenantId, String workspaceId, String promptId, int offset, int limit);

    PromptVersion publish(String tenantId, String workspaceId, String promptId, int version, String actorId);
}
