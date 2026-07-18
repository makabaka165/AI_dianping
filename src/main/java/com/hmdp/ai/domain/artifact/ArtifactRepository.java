package com.hmdp.ai.domain.artifact;import java.util.Optional;public interface ArtifactRepository {Optional<ArtifactRecord>find(String tenantId,String workspaceId,String artifactId);}
