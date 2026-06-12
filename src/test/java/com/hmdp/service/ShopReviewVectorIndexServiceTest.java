package com.hmdp.service;

import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopReviewVectorIndexServiceTest {

    @Mock
    private BlogMapper blogMapper;

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
        ReflectionTestUtils.setField(service, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(service, "documentFactory", documentFactory);
        ReflectionTestUtils.setField(service, "aiMetricsService", aiMetricsService);
        ReflectionTestUtils.setField(service, "ragEnabled", true);
        ReflectionTestUtils.setField(service, "reviewRagEnabled", true);
        ReflectionTestUtils.setField(service, "minScore", 0.5);
        ReflectionTestUtils.setField(service, "maxVectorCandidates", 20);
        ReflectionTestUtils.setField(service, "backfillPageSize", 200);
    }

    @Test
    void indexBlogShouldWriteActiveReviewToEmbeddingStore() {
        Blog blog = activeBlog(1L, "服务很好，适合聚餐");
        when(blogMapper.selectById(1L)).thenReturn(blog);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(embeddingStore.add(any(Embedding.class), any(TextSegment.class))).thenReturn("embedding-1");

        ShopRagRebuildResult result = service.indexBlog(1L);

        assertThat(result.getIndexed()).isEqualTo(1);
        assertThat(result.getFailed()).isZero();
        verify(embeddingStore).add(any(Embedding.class), any(TextSegment.class));
    }

    @Test
    void searchShouldFilterInactiveAndStaleVectorMatches() {
        Blog active = activeBlog(1L, "服务很好，适合聚餐");
        Blog stale = activeBlog(2L, "旧内容");
        TextSegment activeSegment = documentFactory.toSegment(active);
        TextSegment staleSegment = documentFactory.toSegment(stale);
        Blog changed = activeBlog(2L, "新内容");

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f, 0.2f})));
        when(embeddingStore.findRelevant(any(Embedding.class), eq(20), eq(0.5))).thenReturn(List.of(
                new EmbeddingMatch<>(0.8, "e1", Embedding.from(new float[]{0.1f, 0.2f}), activeSegment),
                new EmbeddingMatch<>(0.9, "e2", Embedding.from(new float[]{0.1f, 0.2f}), staleSegment)
        ));
        when(blogMapper.selectById(1L)).thenReturn(active);
        when(blogMapper.selectById(2L)).thenReturn(changed);

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

    private Blog activeBlog(Long id, String content) {
        return new Blog()
                .setId(id)
                .setShopId(10L)
                .setContent(content)
                .setLiked(5)
                .setStatus(1)
                .setDeleted(0)
                .setCreateTime(LocalDateTime.now().minusDays(id));
    }
}
