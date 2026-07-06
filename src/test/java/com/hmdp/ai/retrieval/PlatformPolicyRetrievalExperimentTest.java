package com.hmdp.ai.retrieval;

import com.hmdp.ai.infra.DocumentQualityAssessment;
import com.hmdp.ai.port.PlatformPolicyDocumentPort;
import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformPolicyRetrievalExperimentTest {

    private static final String ROOT = "document-quality/platform-policy/";

    private final DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();

    @Test
    void refundQuestionShouldRetrieveRefundPolicyBeforeUnrelatedPolicyDocument() throws IOException {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        add(store, "excellent_refund_policy.md");
        add(store, "fair_account_policy.md");
        add(store, "poor_unrelated_policy.md");

        QualityBasedContentRetriever retriever = QualityBasedContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .minScore(0.55)
                .maxResults(3)
                .build();

        List<Content> contents = retriever.retrieve(Query.from("平台重复扣款后怎么申请退款，需要投诉商家怎么办？"));

        assertThat(contents).isNotEmpty();
        assertThat(contents.get(0).textSegment().text()).contains("退款与投诉处理规则");
        assertThat(contents)
                .extracting(content -> content.textSegment().text())
                .anySatisfy(text -> assertThat(text).contains("退款会按原支付路径返回"));
        assertThat(contents)
                .extracting(content -> content.textSegment().text())
                .noneSatisfy(text -> assertThat(text).contains("城市旅行随笔"));
    }

    @Test
    void accountQuestionShouldRetrieveAccountPolicyBeforeRefundPolicy() throws IOException {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        add(store, "excellent_refund_policy.md");
        add(store, "fair_account_policy.md");
        add(store, "poor_unrelated_policy.md");

        QualityBasedContentRetriever retriever = QualityBasedContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .minScore(0.55)
                .maxResults(2)
                .build();

        List<Content> contents = retriever.retrieve(Query.from("账号登录收不到验证码怎么办？"));

        assertThat(contents).isNotEmpty();
        assertThat(contents.get(0).textSegment().text()).contains("账号登录常见问题");
    }

    private void add(InMemoryEmbeddingStore<TextSegment> store, String file) throws IOException {
        TextSegment segment = TextSegment.from(load(file));
        store.add(embeddingModel.embed(segment.text()).content(), segment);
    }

    private String load(String file) throws IOException {
        try (InputStream inputStream = new ClassPathResource(ROOT + file).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test
    void shouldFilterDeletedMissingAndHashMismatchPlatformPolicyChunks() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        addSegment(store, "active-doc", "refund active policy", DocumentStatus.PUBLISHED);
        addSegment(store, "deleted-doc", "refund deleted policy", DocumentStatus.PUBLISHED);
        addSegment(store, "missing-doc", "refund missing policy", DocumentStatus.PUBLISHED);
        addSegment(store, "changed-doc", "refund old policy", DocumentStatus.PUBLISHED);
        PlatformPolicyDocumentPort documentPort = new FakePlatformPolicyDocumentPort(Map.of(
                "active-doc", "refund active policy",
                "changed-doc", "refund new policy"));

        QualityBasedContentRetriever retriever = QualityBasedContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .platformPolicyDocumentPort(documentPort)
                .minScore(0.0)
                .maxResults(4)
                .build();

        List<Content> contents = retriever.retrieve(Query.from("refund policy"));

        assertThat(contents)
                .extracting(content -> content.textSegment().metadata().getString(PlatformPolicyVectorDocumentFactory.META_DOCUMENT_ID))
                .containsExactly("active-doc");
    }

    @Test
    void shouldOverFetchWhenStaleChunksWouldFillRequestedTopK() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        addSegment(store, "stale-1", "refund policy exact", DocumentStatus.PUBLISHED);
        addSegment(store, "stale-2", "refund policy exact", DocumentStatus.PUBLISHED);
        addSegment(store, "active-doc", "refund policy exact", DocumentStatus.PUBLISHED);
        PlatformPolicyDocumentPort documentPort = new FakePlatformPolicyDocumentPort(Map.of(
                "active-doc", "refund policy exact"));

        QualityBasedContentRetriever retriever = QualityBasedContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .platformPolicyDocumentPort(documentPort)
                .minScore(0.0)
                .maxResults(1)
                .build();

        List<Content> contents = retriever.retrieve(Query.from("refund policy exact"));

        assertThat(contents)
                .extracting(content -> content.textSegment().metadata().getString(PlatformPolicyVectorDocumentFactory.META_DOCUMENT_ID))
                .containsExactly("active-doc");
    }

    @Test
    void shouldReturnEmptyWhenAllPlatformPolicyChunksAreStale() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        addSegment(store, "stale-1", "refund policy exact", DocumentStatus.PUBLISHED);
        addSegment(store, "stale-2", "refund policy exact", DocumentStatus.PUBLISHED);
        PlatformPolicyDocumentPort documentPort = new FakePlatformPolicyDocumentPort(Map.of());

        QualityBasedContentRetriever retriever = QualityBasedContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .platformPolicyDocumentPort(documentPort)
                .minScore(0.0)
                .maxResults(2)
                .build();

        List<Content> contents = retriever.retrieve(Query.from("refund policy exact"));

        assertThat(contents).isEmpty();
    }

    private void addSegment(InMemoryEmbeddingStore<TextSegment> store,
                            String documentId,
                            String content,
                            DocumentStatus status) {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setId(documentId);
        metadata.setStatus(status);
        TextSegment segment = TextSegment.from(content,
                PlatformPolicyVectorDocumentFactory.metadata(documentId, metadata, content));
        store.add(embeddingModel.embed(segment.text()).content(), segment);
    }

    private static class FakePlatformPolicyDocumentPort implements PlatformPolicyDocumentPort {

        private final Map<String, String> activeDocuments;

        private FakePlatformPolicyDocumentPort(Map<String, String> activeDocuments) {
            this.activeDocuments = activeDocuments;
        }

        @Override
        public void saveImportedDocument(DocumentMetadata metadata,
                                         DocumentQualityAssessment qualityAssessment,
                                         Document document) {
        }

        @Override
        public boolean isActiveDocumentChunk(String documentId, String contentHash) {
            String content = activeDocuments.get(documentId);
            return content != null
                    && PlatformPolicyVectorDocumentFactory.contentHash(documentId, content).equals(contentHash);
        }

        @Override
        public void archiveMissingImportedDocuments(Set<String> activeDocumentIds) {
        }
    }
}
