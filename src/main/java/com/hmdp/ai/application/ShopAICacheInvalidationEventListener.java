package com.hmdp.ai.application;

import com.hmdp.entity.Blog;
import com.hmdp.event.BlogLikeChangedEvent;
import com.hmdp.event.BlogPublishedEvent;
import com.hmdp.mapper.BlogMapper;
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
    private ShopAICacheInvalidationService shopAICacheInvalidationService;

    @Async("blogEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlogPublished(BlogPublishedEvent event) {
        clearByBlogId(event.getBlogId(), "publish");
    }

    @Async("blogEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlogLikeChanged(BlogLikeChangedEvent event) {
        clearByBlogId(event.getBlogId(), "like");
    }

    private void clearByBlogId(Long blogId, String reason) {
        if (blogId == null || blogId <= 0) {
            return;
        }
        try {
            Blog blog = blogMapper.selectById(blogId);
            if (blog == null || blog.getShopId() == null) {
                return;
            }
            shopAICacheInvalidationService.clearShopRelatedCaches(blog.getShopId());
            log.debug("Cleared shop AI caches after blog {}, blogId={}, shopId={}",
                    reason, blogId, blog.getShopId());
        } catch (RuntimeException e) {
            log.warn("Clear shop AI caches after blog event failed, reason={}, blogId={}", reason, blogId, e);
        }
    }
}
