package com.hmdp.event.listener;

import com.hmdp.ai.port.AiReviewChangeHandler;
import com.hmdp.entity.Blog;
import com.hmdp.event.BlogLikeChangedEvent;
import com.hmdp.event.BlogPublishedEvent;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.ShopStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.annotation.Resource;

@Component
@Slf4j
public class ShopAICacheInvalidationEventListener {

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private ShopStatsService shopStatsService;

    @Resource
    private AiReviewChangeHandler handler;

    @Async("blogEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlogPublished(BlogPublishedEvent event) {
        evictShopStats(event.getBlogId());
        handler.onReviewPublished(event.getBlogId());
    }

    @Async("blogEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlogLikeChanged(BlogLikeChangedEvent event) {
        evictShopStats(event.getBlogId());
        handler.onReviewLikeChanged(event.getBlogId());
    }

    private void evictShopStats(Long blogId) {
        if (blogId == null || blogId <= 0) {
            return;
        }
        try {
            Blog blog = blogMapper.selectById(blogId);
            if (blog == null || blog.getShopId() == null) {
                return;
            }
            shopStatsService.evictShopStatsCache(blog.getShopId());
        } catch (RuntimeException e) {
            log.warn("Evict shop stats cache after blog event failed, blogId={}", blogId, e);
        }
    }
}
