package com.hmdp.ai.application;

import com.hmdp.ai.port.ReviewDataPort;
import com.hmdp.dto.ai.ReviewDoc;
import com.hmdp.ai.retrieval.ShopReviewVectorIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiReviewChangeHandlerTest {

    @Mock
    private ReviewDataPort reviewDataPort;

    @Mock
    private ShopAICacheInvalidationService cacheInvalidationService;

    @Mock
    private ShopReviewVectorIndexService vectorIndexService;

    private DefaultAiReviewChangeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DefaultAiReviewChangeHandler();
        ReflectionTestUtils.setField(handler, "reviewDataPort", reviewDataPort);
        ReflectionTestUtils.setField(handler, "shopAICacheInvalidationService", cacheInvalidationService);
        ReflectionTestUtils.setField(handler, "shopReviewVectorIndexService", vectorIndexService);
    }

    @Test
    void shouldClearShopAiCacheAndIndexAfterReviewPublished() {
        ReviewDoc review = ReviewDoc.builder().id(11L).shopId(7L).build();
        when(reviewDataPort.getReview(11L)).thenReturn(review);

        handler.onReviewPublished(11L);

        verify(cacheInvalidationService).clearShopRelatedCaches(7L);
        verify(vectorIndexService).indexBlog(review);
    }

    @Test
    void shouldClearShopAiCacheAfterReviewLikeChangedWithoutIndexing() {
        ReviewDoc review = ReviewDoc.builder().id(12L).shopId(8L).build();
        when(reviewDataPort.getReview(12L)).thenReturn(review);

        handler.onReviewLikeChanged(12L);

        verify(cacheInvalidationService).clearShopRelatedCaches(8L);
        verify(vectorIndexService, never()).indexBlog(org.mockito.ArgumentMatchers.any(ReviewDoc.class));
    }

    @Test
    void shouldIgnoreReviewWithoutShopId() {
        when(reviewDataPort.getReview(13L)).thenReturn(ReviewDoc.builder().id(13L).build());

        handler.onReviewPublished(13L);

        verify(cacheInvalidationService, never()).clearShopRelatedCaches(org.mockito.ArgumentMatchers.anyLong());
    }
}
