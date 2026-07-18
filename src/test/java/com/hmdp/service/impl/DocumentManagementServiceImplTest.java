package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.adapter.ai.PlatformPolicyDocumentAdapter;
import com.hmdp.ai.infra.DocumentQualityAssessor;
import com.hmdp.ai.retrieval.PlatformPolicyVectorDocumentFactory;
import com.hmdp.entity.AiDocument;
import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import com.hmdp.mapper.AiDocumentMapper;
import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DocumentManagementServiceImplTest {

    @Mock
    private AiDocumentMapper aiDocumentMapper;

    private DocumentManagementServiceImpl service;
    private Map<String, AiDocument> rows;

    @BeforeEach
    void setUp() {
        rows = new LinkedHashMap<>();
        service = new DocumentManagementServiceImpl();
        ReflectionTestUtils.setField(service, "aiDocumentMapper", aiDocumentMapper);
        ReflectionTestUtils.setField(service, "qualityAssessor", new DocumentQualityAssessor());
        lenient().when(aiDocumentMapper.selectById(anyString())).thenAnswer(invocation -> rows.get(invocation.getArgument(0)));
        lenient().when(aiDocumentMapper.selectList(any(QueryWrapper.class))).thenAnswer(invocation -> new ArrayList<>(rows.values()));
        lenient().doAnswer(invocation -> {
            AiDocument row = invocation.getArgument(0);
            rows.put(row.getId(), row);
            return 1;
        }).when(aiDocumentMapper).insert(any(AiDocument.class));
        lenient().doAnswer(invocation -> {
            AiDocument row = invocation.getArgument(0);
            rows.put(row.getId(), row);
            return 1;
        }).when(aiDocumentMapper).updateById(any(AiDocument.class));
    }

    @Test
    void addDocumentShouldPersistAndReadMetadataAndContent() {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setTitle("policy.md");
        metadata.setSource("unit-test");
        metadata.setFileType("md");

        String documentId = service.addDocument(Document.from("平台规则：退款流程需要提交凭证。"), metadata);

        assertThat(documentId).isNotBlank();
        assertThat(service.listAllDocuments()).extracting(DocumentMetadata::getId).contains(documentId);
        assertThat(service.getDocumentMetadata(documentId)).isPresent();
        assertThat(service.getDocument(documentId)).isPresent();
        assertThat(service.getDocument(documentId).get().text()).contains("退款流程");
        assertThat(rows.get(documentId).getStatus()).isEqualTo(DocumentStatus.PUBLISHED.name());
    }

    @Test
    void deleteDocumentShouldMarkDeletedAndHideFromGet() {
        String documentId = service.addDocument(Document.from("这是一段足够长度的测试文档内容。"), new DocumentMetadata());

        boolean deleted = service.deleteDocument(documentId);

        assertThat(deleted).isTrue();
        assertThat(rows.get(documentId).getStatus()).isEqualTo(DocumentStatus.DELETED.name());
        assertThat(service.getDocumentMetadata(documentId)).isEmpty();
    }
    @Test
    void getDocumentShouldExposeVectorValidationMetadata() {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setId("doc-1");
        metadata.setTitle("policy.md");

        service.addDocument(Document.from("validation content"), metadata);

        Document document = service.getDocument("doc-1").orElseThrow();
        assertThat(document.metadata().getString(PlatformPolicyVectorDocumentFactory.META_DOCUMENT_ID)).isEqualTo("doc-1");
        assertThat(document.metadata().getString(PlatformPolicyVectorDocumentFactory.META_STATUS)).isEqualTo(DocumentStatus.PUBLISHED.name());
        assertThat(document.metadata().getString(PlatformPolicyVectorDocumentFactory.META_CONTENT_HASH))
                .isEqualTo(PlatformPolicyVectorDocumentFactory.contentHash("doc-1", "validation content"));
    }

    @Test
    void platformPolicyAdapterShouldRejectDeletedMissingAndHashMismatchChunks() {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setId("doc-2");
        service.addDocument(Document.from("active policy content"), metadata);

        PlatformPolicyDocumentAdapter adapter = new PlatformPolicyDocumentAdapter();
        ReflectionTestUtils.setField(adapter, "documentManagementService", service);

        String currentHash = PlatformPolicyVectorDocumentFactory.contentHash("doc-2", "active policy content");
        assertThat(adapter.isActiveDocumentChunk("doc-2", currentHash)).isTrue();
        assertThat(adapter.isActiveDocumentChunk("doc-2",
                PlatformPolicyVectorDocumentFactory.contentHash("doc-2", "old content"))).isFalse();
        assertThat(adapter.isActiveDocumentChunk("missing-doc", currentHash)).isFalse();

        service.deleteDocument("doc-2");

        assertThat(adapter.isActiveDocumentChunk("doc-2", currentHash)).isFalse();
    }

    @Test
    void platformPolicyAdapterShouldOnlyAcceptPublishedDocuments() {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setId("doc-3");
        metadata.setStatus(DocumentStatus.DRAFT);
        service.addDocument(Document.from("draft policy content"), metadata);

        PlatformPolicyDocumentAdapter adapter = new PlatformPolicyDocumentAdapter();
        ReflectionTestUtils.setField(adapter, "documentManagementService", service);

        String currentHash = PlatformPolicyVectorDocumentFactory.contentHash("doc-3", "draft policy content");

        assertThat(adapter.isActiveDocumentChunk("doc-3", currentHash)).isFalse();
    }

    @Test
    void platformPolicyAdapterShouldArchiveMissingImportedDocuments() {
        DocumentMetadata active = importedMetadata("imported-active");
        DocumentMetadata missing = importedMetadata("imported-missing");
        DocumentMetadata manual = new DocumentMetadata();
        manual.setId("manual-doc");
        manual.setSource("manual-upload");

        service.addDocument(Document.from("active imported content"), active);
        service.addDocument(Document.from("missing imported content"), missing);
        service.addDocument(Document.from("manual content"), manual);

        PlatformPolicyDocumentAdapter adapter = new PlatformPolicyDocumentAdapter();
        ReflectionTestUtils.setField(adapter, "documentManagementService", service);

        adapter.archiveMissingImportedDocuments(Collections.singleton("imported-active"));

        assertThat(rows.get("imported-active").getStatus()).isEqualTo(DocumentStatus.PUBLISHED.name());
        assertThat(rows.get("imported-missing").getStatus()).isEqualTo(DocumentStatus.ARCHIVED.name());
        assertThat(rows.get("manual-doc").getStatus()).isEqualTo(DocumentStatus.PUBLISHED.name());
        assertThat(adapter.isActiveDocumentChunk("imported-missing",
                PlatformPolicyVectorDocumentFactory.contentHash("imported-missing", "missing imported content"))).isFalse();
    }

    private DocumentMetadata importedMetadata(String id) {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setId(id);
        metadata.setSource("system-initial-import");
        return metadata;
    }
}
