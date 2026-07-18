package com.hmdp.ai.domain.model;

import java.util.List;
import java.util.Optional;

public interface ModelProfileRepository {
    ModelProfile create(ModelProfile profile, String actorId);

    ModelProfile update(ModelProfile profile, int expectedRevision, String actorId);

    Optional<ModelProfile> findById(String tenantId, String workspaceId, String id);

    List<ModelProfile> findPage(String tenantId, String workspaceId, int offset, int limit);

    long count(String tenantId, String workspaceId);
}
