package com.hmdp.ai.application.artifact;

import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.artifact.ArtifactRecord;
import com.hmdp.ai.domain.artifact.ArtifactRepository;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArtifactApplicationServiceTest {
    @Test
    void rejectsArtifactOwnedByAnotherUser() {
        ArtifactRepository repository = mock(ArtifactRepository.class);
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        AiAccessGuard access = mock(AiAccessGuard.class);
        AiSecurityContext context = context("user-a", "t", "w");
        when(access.require(AiPermission.ARTIFACT_READ)).thenReturn(context);
        when(repository.find("t", "w", "artifact")).thenReturn(Optional.of(new ArtifactRecord(
                "artifact", "t", "w", "run", "file.txt", "text/plain", "key", 4,
                "user-b", "ACTIVE")));
        ArtifactApplicationService service = new ArtifactApplicationService(repository, storage, access, "bucket");

        assertThatThrownBy(() -> service.download("artifact")).isInstanceOf(BusinessException.class);

        verifyNoInteractions(storage);
    }

    @Test
    void scopesLookupToCurrentTenantAndWorkspace() {
        ArtifactRepository repository = mock(ArtifactRepository.class);
        AiAccessGuard access = mock(AiAccessGuard.class);
        when(access.require(AiPermission.ARTIFACT_READ)).thenReturn(context("user", "tenant-a", "workspace-a"));
        when(repository.find("tenant-a", "workspace-a", "artifact")).thenReturn(Optional.empty());
        ArtifactApplicationService service = new ArtifactApplicationService(repository,
                mock(ObjectStoragePort.class), access, "bucket");

        assertThatThrownBy(() -> service.download("artifact")).hasMessage("ARTIFACT_NOT_FOUND");

        verify(repository).find("tenant-a", "workspace-a", "artifact");
    }

    private AiSecurityContext context(String userId, String tenantId, String workspaceId) {
        return new AiSecurityContext(userId, new TenantContext(tenantId), new WorkspaceContext(workspaceId),
                new AuthorizationContext(EnumSet.of(AiPermission.ARTIFACT_READ)), false);
    }
}
