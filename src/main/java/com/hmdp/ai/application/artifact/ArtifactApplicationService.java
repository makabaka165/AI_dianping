package com.hmdp.ai.application.artifact;

import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.artifact.ArtifactContent;
import com.hmdp.ai.domain.artifact.ArtifactRecord;
import com.hmdp.ai.domain.artifact.ArtifactRepository;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.common.ErrorCode;
import com.hmdp.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ArtifactApplicationService {
    private final ArtifactRepository repository;
    private final ObjectStoragePort storage;
    private final AiAccessGuard access;
    private final String bucket;

    public ArtifactApplicationService(ArtifactRepository repository, ObjectStoragePort storage,
                                      AiAccessGuard access, @Value("${minio.bucket:hmdp-ai}") String bucket) {
        this.repository = repository;
        this.storage = storage;
        this.access = access;
        this.bucket = bucket;
    }

    public ArtifactContent download(String id) {
        AiSecurityContext context = access.require(AiPermission.ARTIFACT_READ);
        ArtifactRecord record = repository.find(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), id)
                .orElseThrow(() -> new IllegalArgumentException("ARTIFACT_NOT_FOUND"));
        if (!record.getCreatedBy().equals(context.getUserId())
                && !context.getAuthorization().has(AiPermission.ADMIN)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return new ArtifactContent(record, storage.get(bucket, record.getObjectKey()));
    }
}
