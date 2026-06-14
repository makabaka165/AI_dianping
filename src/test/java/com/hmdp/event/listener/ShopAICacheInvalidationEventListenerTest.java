package com.hmdp.event.listener;

import com.hmdp.ai.port.AiReviewChangeHandler;
import com.hmdp.entity.Blog;
import com.hmdp.event.BlogLikeChangedEvent;
import com.hmdp.event.BlogPublishedEvent;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.ShopStatsService;
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
    private ShopStatsService shopStatsService;

    @Mock
    private AiReviewChangeHandler handler;

    private ShopAICacheInvalidationEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new ShopAICacheInvalidationEventListener();
        ReflectionTestUtils.setField(listener, "blogMapper", blogMapper);
        ReflectionTestUtils.setField(listener, "shopStatsService", shopStatsService);
        ReflectionTestUtils.setField(listener, "handler", handler);
    }

    @Test
    void shouldEvictShopStatsAndDelegatePublishedEventToHandler() {
        when(blogMapper.selectById(11L)).thenReturn(new Blog().setShopId(7L));

        listener.onBlogPublished(new BlogPublishedEvent(11L, 3L, 1000L));

        verify(shopStatsService).evictShopStatsCache(7L);
        verify(handler).onReviewPublished(11L);
    }

    @Test
    void shouldEvictShopStatsAndDelegateLikeChangedEventToHandler() {
        when(blogMapper.selectById(12L)).thenReturn(new Blog().setShopId(8L));

        listener.onBlogLikeChanged(new BlogLikeChangedEvent(12L, 4L, true, 1000L));

        verify(shopStatsService).evictShopStatsCache(8L);
        verify(handler).onReviewLikeChanged(12L);
    }

    @Test
    void shouldDelegatePublishedEventWhenBlogHasNoShop() {
        when(blogMapper.selectById(13L)).thenReturn(new Blog());

        listener.onBlogPublished(new BlogPublishedEvent(13L, 3L, 1000L));

        verify(shopStatsService, never()).evictShopStatsCache(org.mockito.ArgumentMatchers.anyLong());
        verify(handler).onReviewPublished(13L);
    }
}
