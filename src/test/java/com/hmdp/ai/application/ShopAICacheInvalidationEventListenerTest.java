package com.hmdp.ai.application;

import com.hmdp.entity.Blog;
import com.hmdp.event.BlogLikeChangedEvent;
import com.hmdp.event.BlogPublishedEvent;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.ShopReviewVectorIndexService;
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
class ShopAICacheInvalidationEventListenerTest {

    @Mock
    private BlogMapper blogMapper;

    @Mock
    private ShopAICacheInvalidationService cacheInvalidationService;

    @Mock
    private ShopReviewVectorIndexService vectorIndexService;

    private ShopAICacheInvalidationEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new ShopAICacheInvalidationEventListener();
        ReflectionTestUtils.setField(listener, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(listener, "shopAICacheInvalidationService", cacheInvalidationService);
        ReflectionTestUtils.setField(listener, "shopReviewVectorIndexService", vectorIndexService);
    }

    @Test
    void shouldClearShopAiCacheAfterBlogPublished() {
        Blog blog = new Blog().setId(11L).setShopId(7L);
        when(blogMapper.selectById(11L)).thenReturn(blog);

        listener.onBlogPublished(new BlogPublishedEvent(11L, 3L, 1000L));

        verify(cacheInvalidationService).clearShopRelatedCaches(7L);
        verify(vectorIndexService).indexBlog(blog);
    }

    @Test
    void shouldClearShopAiCacheAfterBlogLikeChanged() {
        when(blogMapper.selectById(12L)).thenReturn(new Blog().setId(12L).setShopId(8L));

        listener.onBlogLikeChanged(new BlogLikeChangedEvent(12L, 4L, true, 1000L));

        verify(cacheInvalidationService).clearShopRelatedCaches(8L);
        verify(vectorIndexService, never()).indexBlog(org.mockito.ArgumentMatchers.any(Blog.class));
    }

    @Test
    void shouldIgnoreBlogWithoutShopId() {
        when(blogMapper.selectById(13L)).thenReturn(new Blog().setId(13L));

        listener.onBlogPublished(new BlogPublishedEvent(13L, 3L, 1000L));

        verify(cacheInvalidationService, never()).clearShopRelatedCaches(org.mockito.ArgumentMatchers.anyLong());
    }
}
