package com.hmdp.ai.retrieval;

import com.hmdp.ai.port.ReviewDataPort;
import com.hmdp.ai.infra.DocumentQualityAssessor;
import com.hmdp.ai.infra.DocumentIndexDecisionService;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ReviewDoc;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import com.hmdp.ai.infra.AiMetricsService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopReviewVectorIndexServiceTest {

    @Mock
    private ReviewDataPort reviewDataPort;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    @Mock
    private AiMetricsService aiMetricsService;

    private ReviewVectorDocumentFactory documentFactory;
    private ShopReviewVectorIndexService service;

    @BeforeEach
    void setUp() {
        documentFactory = new ReviewVectorDocumentFactory();
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingStore<TextSegment>> storeProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> modelProvider = mock(ObjectProvider.class);
        lenient().when(storeProvider.getIfAvailable()).thenReturn(embeddingStore);
        lenient().when(modelProvider.getIfAvailable()).thenReturn(embeddingModel);
        service = new ShopReviewVectorIndexService(storeProvider, modelProvider);
        ReflectionTestUtils.setField(service, "reviewDataPort", reviewDataPort);
        ReflectionTestUtils.setField(service, "documentFactory", documentFactory);
        ReflectionTestUtils.setField(service, "documentQualityAssessor", new DocumentQualityAssessor());
        ReflectionTestUtils.setField(service, "documentIndexDecisionService", new DocumentIndexDecisionService());
        ReflectionTestUtils.setField(service, "aiMetricsService", aiMetricsService);
        ReflectionTestUtils.setField(service, "ragEnabled", true);
        ReflectionTestUtils.setField(service, "reviewRagEnabled", true);
        ReflectionTestUtils.setField(service, "minScore", 0.5);
        ReflectionTestUtils.setField(service, "maxVectorCandidates", 20);
        ReflectionTestUtils.setField(service, "backfillPageSize", 200);
        ReflectionTestUtils.setField(service, "compactEnabled", true);
        ReflectionTestUtils.setField(service, "reviewQualityEnabled", true);
        ReflectionTestUtils.setField(service, "reviewQualityIndexPolicy", "observe_only");
        ReflectionTestUtils.setField(service, "reviewQualityMinScore", 0.45);
    }

    @Test
    void indexBlogShouldWriteActiveReviewToEmbeddingStore() {
        ReviewDoc blog = activeBlog(1L, "服务很好，适合聚餐");
        when(reviewDataPort.getReview(1L)).thenReturn(blog);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("embedding-1");

        ShopRagRebuildResult result = service.indexBlog(1L);

        assertThat(result.getIndexed()).isEqualTo(1);
        assertThat(result.getFailed()).isZero();
        verify(embeddingStore).add(any(Embedding.class), any(TextSegment.class));
    }

    @Test
    void indexBlogShouldKeepIndexingLowQualityReviewInObserveOnlyModeWithQualityMetadata() {
        ReviewDoc blog = activeBlog(1L, "好");
        when(reviewDataPort.getReview(1L)).thenReturn(blog);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("embedding-1");

        ShopRagRebuildResult result = service.indexBlog(1L);

        org.mockito.ArgumentCaptor<TextSegment> segmentCaptor = forClass(TextSegment.class);
        assertThat(result.getIndexed()).isEqualTo(1);
        verify(embeddingStore).add(any(Embedding.class), segmentCaptor.capture());
        TextSegment indexedSegment = segmentCaptor.getValue();
        assertThat(indexedSegment.metadata().getString(ReviewVectorDocumentFactory.META_QUALITY_PROFILE)).isEqualTo("SHOP_REVIEW");
        assertThat(indexedSegment.metadata().getString(ReviewVectorDocumentFactory.META_INDEX_DECISION_ACTION)).isEqualTo("INDEX");
        assertThat(indexedSegment.metadata().getString(ReviewVectorDocumentFactory.META_INDEX_DECISION_POLICY)).isEqualTo("OBSERVE_ONLY");
        assertThat(indexedSegment.metadata().getDouble(ReviewVectorDocumentFactory.META_QUALITY_SCORE)).isLessThan(0.70);
    }

    @Test
    void searchShouldFilterInactiveAndStaleVectorMatches() {
        ReviewDoc active = activeBlog(1L, "服务很好，适合聚餐");
        ReviewDoc stale = activeBlog(2L, "旧内容");
        TextSegment activeSegment = documentFactory.toSegment(active);
        TextSegment staleSegment = documentFactory.toSegment(stale);
        ReviewDoc changed = activeBlog(2L, "新内容");

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(embeddingStore.findRelevant(any(Embedding.class), eq(20), eq(0.5))).thenReturn(List.of(
                new EmbeddingMatch<>(0.8, "e1", Embedding.from(new float[]{0.1f, 0.2f}), activeSegment),
                new EmbeddingMatch<>(0.9, "e2", Embedding.from(new float[]{0.1f, 0.2f}), staleSegment)
        ));
        when(reviewDataPort.getReview(1L)).thenReturn(active);
        when(reviewDataPort.getReview(2L)).thenReturn(changed);

        List<EvidenceItem> evidence = service.search(10L, "服务", null, 5);

        assertThat(evidence).extracting(EvidenceItem::getId).containsExactly("review:1");
    }

    @Test
    void rebuildShopShouldSkipWhenDisabled() {
        ReflectionTestUtils.setField(service, "reviewRagEnabled", false);

        ShopRagRebuildResult result = service.rebuildShop(10L, 100);

        assertThat(result.getIndexed()).isZero();
        assertThat(result.getMessage()).contains("disabled");
    }

    @Test
    void rebuildAllWithListenerShouldReportProgressAfterEachShop() {
        ReviewDoc first = activeBlog(1L, "服务稳定");
        ReviewDoc second = activeBlog(2L, "环境不错");
        second.setShopId(20L);
        when(reviewDataPort.findActiveShopIdsForRag(2)).thenReturn(List.of(10L, 20L));
        when(reviewDataPort.findActiveReviewsForRag(10L, 3)).thenReturn(List.of(first));
        when(reviewDataPort.findActiveReviewsForRag(20L, 3)).thenReturn(List.of(second));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("embedding");
        java.util.List<String> progress = new java.util.ArrayList<>();

        ShopRagRebuildResult result = service.rebuildAll(2, 3,
                (current, total) -> progress.add(current + "/" + total));

        assertThat(result.getIndexed()).isEqualTo(2);
        assertThat(progress).containsExactly("1/2", "2/2");
    }

    @Test
    void rebuildAllOldSignatureShouldStillWork() {
        ReviewDoc blog = activeBlog(1L, "服务稳定");
        when(reviewDataPort.findActiveShopIdsForRag(1)).thenReturn(List.of(10L));
        when(reviewDataPort.findActiveReviewsForRag(10L, 3)).thenReturn(List.of(blog));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("embedding");

        ShopRagRebuildResult result = service.rebuildAll(1, 3);

        assertThat(result.getIndexed()).isEqualTo(1);
    }

    @Test
    void compactShopShouldRefreshVectorsAndDiscloseDeletionLimitation() {
        ReviewDoc blog = activeBlog(1L, "服务稳定，适合聚餐");
        when(reviewDataPort.findActiveReviewsForRag(10L, 3)).thenReturn(List.of(blog));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("embedding-1");

        ShopRagRebuildResult result = service.compactShop(10L, 3);

        assertThat(result.getIndexed()).isEqualTo(1);
        assertThat(result.getMessage()).contains("does not support precise old vector deletion");
        verify(embeddingStore).add(any(Embedding.class), any(TextSegment.class));
        verify(aiMetricsService).recordRagIndex(eq("compact_shop"), eq(1), eq(0), eq(0), anyLong());
    }

    @Test
    void compactShopShouldReturnUnavailableWhenStoreOrModelDisabled() {
        ReflectionTestUtils.setField(service, "reviewRagEnabled", false);

        ShopRagRebuildResult result = service.compactShop(10L, 3);

        assertThat(result.getIndexed()).isZero();
        assertThat(result.getMessage()).contains("unavailable");
        verify(embeddingStore, never()).add(any(Embedding.class), any(TextSegment.class));
    }

    private ReviewDoc activeBlog(Long id, String content) {
        return ReviewDoc.builder()
                .id(id)
                .shopId(10L)
                .content(content)
                .liked(5)
                .status(1)
                .deleted(0)
                .createTime(LocalDateTime.now().minusDays(id))
                .build();
    }
}
