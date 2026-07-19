package com.hmdp.ai.domain.model;

import java.util.List;
import java.util.Optional;

public interface ModelProfileVersionRepository {
    int nextVersion(String tenantId, String workspaceId, String profileId);

    ModelProfileVersion create(ModelProfileVersion version, String actorId);

    Optional<ModelProfileVersion> findById(String tenantId, String workspaceId, String id);

    Optional<ModelProfileVersion> findByProfileAndVersion(String tenantId, String workspaceId,
                                                           String profileId, int version);

    Optional<ModelProfileVersion> findPublished(String tenantId, String workspaceId, String profileId);

    List<ModelProfileVersion> findVersions(String tenantId, String workspaceId, String profileId,
                                           int offset, int limit);

    ModelProfileVersion publish(String tenantId, String workspaceId, String profileId, int version,
                                String actorId);
}
