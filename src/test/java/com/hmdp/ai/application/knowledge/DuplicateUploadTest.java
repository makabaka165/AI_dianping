package com.hmdp.ai.application.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.knowledge.IngestionDispatcher;
import com.hmdp.ai.domain.knowledge.IngestionJob;
import com.hmdp.ai.domain.knowledge.IngestionStatus;
import com.hmdp.ai.domain.knowledge.KnowledgeBaseVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeDocumentVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgePublishValidator;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.ObjectStoragePort;
import com.hmdp.ai.domain.knowledge.parsing.DocumentInspectionPort;
import com.hmdp.ai.domain.knowledge.parsing.FileInspection;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DuplicateUploadTest {
    @Test
    void duplicateShaReturnsExistingJobWithoutWritingObjectAgain() throws Exception {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        ObjectStoragePort objects = mock(ObjectStoragePort.class);
        DocumentInspectionPort inspection = mock(DocumentInspectionPort.class);
        AiAccessGuard access = mock(AiAccessGuard.class);
        AiSecurityContext context = new AiSecurityContext("u", new TenantContext("t"),
                new WorkspaceContext("w"), new AuthorizationContext(EnumSet.of(AiPermission.KNOWLEDGE_WRITE)),
                false);
        KnowledgeBaseVersion knowledgeVersion = new KnowledgeBaseVersion("kbv", "t", "w", "kb", 1,
                "model", 3, "{}", "{}", "idx", "PENDING", "DRAFT");
        when(access.require(AiPermission.KNOWLEDGE_WRITE)).thenReturn(context);
        when(repository.findVersionNumber("t", "w", "kb", 1)).thenReturn(Optional.of(knowledgeVersion));
        when(inspection.inspect(any(), eq("a.txt"), eq("text/plain")))
                .thenReturn(new FileInspection("abc", "text/plain", 3));
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion("dv", "t", "w", "kb", "d", 1,
                "o", "b", "a.txt", "text/plain", 3, "abc", "PUBLISHED");
        IngestionJob job = new IngestionJob("job", "t", "w", "kb", "kbv", "d", "dv",
                IngestionStatus.PUBLISHED, 100, 1, null, null, "{}");
        when(repository.findBySha("t", "w", "kb", "abc")).thenReturn(Optional.of(version));
        when(repository.findJobByDocumentVersionAndKnowledgeVersion("dv", "kbv"))
                .thenReturn(Optional.of(job));
        ObjectMapper mapper = new ObjectMapper();
        KnowledgeIngestionApplicationService service = new KnowledgeIngestionApplicationService(repository,
                objects, inspection, mock(IngestionDispatcher.class), mock(KnowledgeIndexPort.class),
                new KnowledgePublishValidator(mapper), access, new AiIdGenerator(),
                new ContentHashService(mapper), mapper);

        com.hmdp.ai.application.dto.knowledge.IngestionCreatedResponse response = service.upload(
                "kb", 1, "A", "a.txt", "text/plain", new byte[]{1, 2, 3});

        assertTrue(response.isDuplicate());
        assertEquals("job", response.getJobId());
        verifyNoInteractions(objects);
    }
}
