package com.hmdp.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.knowledge.KnowledgeIngestionApplicationService;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.knowledge.IngestionDispatcher;
import com.hmdp.ai.domain.knowledge.KnowledgeBaseVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeDocument;
import com.hmdp.ai.domain.knowledge.KnowledgeDocumentVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgePublishValidator;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.parsing.DocumentInspectionPort;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublishedKnowledgeVersionImmutableTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private KnowledgeRepository repository;
    private ObjectStoragePort objects;
    private DocumentInspectionPort parsers;
    private IngestionDispatcher worker;
    private KnowledgeIndexPort index;
    private KnowledgeIngestionApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeRepository.class);
        objects = mock(ObjectStoragePort.class);
        parsers = mock(DocumentInspectionPort.class);
        worker = mock(IngestionDispatcher.class);
        index = mock(KnowledgeIndexPort.class);
        AiAccessGuard access = mock(AiAccessGuard.class);
        when(access.require(AiPermission.KNOWLEDGE_WRITE)).thenReturn(security());
        service = new KnowledgeIngestionApplicationService(repository, objects, parsers, worker, index,
                new KnowledgePublishValidator(mapper), access, mock(AiIdGenerator.class),
                new ContentHashService(mapper), mapper);
    }

    @Test
    void publishedVersionRejectsUploadBeforeObjectStorageOrParsing() {
        when(repository.findVersionNumber("tenant", "workspace", "kb", 1))
                .thenReturn(Optional.of(publishedVersion()));

        assertThatThrownBy(() -> service.upload("kb", 1, "policy", "policy.txt",
                "text/plain", "content".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot accept ingestion");

        verifyNoInteractions(objects, parsers, worker, index);
        verify(repository, never()).registerUpload(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void publishedVersionRejectsDocumentReindex() {
        KnowledgeDocument document = new KnowledgeDocument("document", "tenant", "workspace", "kb",
                "doc", "Policy", "UPLOAD", 1, "ACTIVE");
        KnowledgeDocumentVersion documentVersion = new KnowledgeDocumentVersion("document-version", "tenant",
                "workspace", "kb", "document", 1, "object", "bucket", "policy.txt", "text/plain",
                7, "sha", "PUBLISHED");
        when(repository.findDocument("tenant", "workspace", "document"))
                .thenReturn(Optional.of(document));
        when(repository.findCurrentDocumentVersion("tenant", "workspace", "document"))
                .thenReturn(Optional.of(documentVersion));
        when(repository.findVersionNumber("tenant", "workspace", "kb", 1))
                .thenReturn(Optional.of(publishedVersion()));

        assertThatThrownBy(() -> service.reindex("document", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot accept ingestion");

        verify(repository, never()).registerReindex(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(worker, index);
    }

    @Test
    void documentBoundToPublishedVersionCannotBeDeleted() {
        when(repository.isDocumentBoundToImmutableVersion("tenant", "workspace", "document"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.deleteDocument("document"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PUBLISHED_KNOWLEDGE_VERSION_IMMUTABLE");

        verify(repository, never()).deleteDocument("tenant", "workspace", "document", "user");
        verifyNoInteractions(index, objects);
    }

    private KnowledgeBaseVersion publishedVersion() {
        return new KnowledgeBaseVersion("kb-version", "tenant", "workspace", "kb", 1,
                "embedding-model", 3, "{}", "{}", "index-v1", "READY", "PUBLISHED");
    }

    private AiSecurityContext security() {
        return new AiSecurityContext("user", new TenantContext("tenant"), new WorkspaceContext("workspace"),
                new AuthorizationContext(EnumSet.of(AiPermission.KNOWLEDGE_WRITE)), false);
    }
}
