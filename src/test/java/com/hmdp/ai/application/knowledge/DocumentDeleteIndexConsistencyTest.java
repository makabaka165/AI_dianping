package com.hmdp.ai.application.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.knowledge.IngestionDispatcher;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgePublishValidator;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.StoredObject;
import com.hmdp.ai.domain.knowledge.parsing.DocumentInspectionPort;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentDeleteIndexConsistencyTest {
    @Test
    void removesEveryPersistedChunkFromItsIndexAfterDocumentDelete() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeIndexPort index = mock(KnowledgeIndexPort.class);
        ObjectStoragePort objects = mock(ObjectStoragePort.class);
        AiAccessGuard access = mock(AiAccessGuard.class);
        AiSecurityContext context = new AiSecurityContext("user", new TenantContext("tenant"),
                new WorkspaceContext("workspace"),
                new AuthorizationContext(EnumSet.of(AiPermission.KNOWLEDGE_WRITE)), false);
        when(access.require(AiPermission.KNOWLEDGE_WRITE)).thenReturn(context);
        when(repository.findChunkIds("tenant", "workspace", "document"))
                .thenReturn(Arrays.asList("chunk-1", "chunk-2"));
        when(repository.findChunksByIds("tenant", "workspace", Arrays.asList("chunk-1", "chunk-2")))
                .thenReturn(Arrays.asList(chunk("chunk-1", "index-v1"), chunk("chunk-2", "index-v1")));
        when(repository.findDocumentObjects("tenant", "workspace", "document"))
                .thenReturn(Arrays.asList(
                        storedObject("bucket", "object-v1"),
                        storedObject("bucket", "object-v2")));
        when(repository.deleteDocument("tenant", "workspace", "document", "user")).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        KnowledgeIngestionApplicationService service = new KnowledgeIngestionApplicationService(repository,
                objects, mock(DocumentInspectionPort.class), mock(IngestionDispatcher.class),
                index, new KnowledgePublishValidator(mapper), access, new AiIdGenerator(),
                new ContentHashService(mapper), mapper);

        service.deleteDocument("document");

        verify(index).delete("index-v1", Arrays.asList("chunk-1", "chunk-2"));
        verify(objects).delete("bucket", "object-v1");
        verify(objects).delete("bucket", "object-v2");
    }

    private StoredObject storedObject(String bucket, String key) {
        return new StoredObject(key, bucket, "text/plain", 4, "sha", "policy.txt");
    }

    private KnowledgeChunk chunk(String id, String indexVersion) {
        return new KnowledgeChunk(id, "tenant", "workspace", "kb", 1, "document", 1, "dv",
                indexVersion, 0, "text", "text", id, 3, new float[]{1, 0, 0}, 1,
                "text/plain", null, null, null, null, null, null, null, null, 0, 4, "{}");
    }
}
