package com.hmdp.ai.application;

import com.hmdp.entity.Blog;
import com.hmdp.event.BlogLikeChangedEvent;
import com.hmdp.event.BlogPublishedEvent;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.ShopReviewVectorIndexService;
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

    @Resource
    private ShopReviewVectorIndexService shopReviewVectorIndexService;

    @Async("blogEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlogPublished(BlogPublishedEvent event) {
        clearByBlogId(event.getBlogId(), "publish", true);
    }

    @Async("blogEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlogLikeChanged(BlogLikeChangedEvent event) {
        clearByBlogId(event.getBlogId(), "like", false);
    }

    private void clearByBlogId(Long blogId, String reason, boolean indexReview) {
        if (blogId == null || blogId <= 0) {
            return;
        }
        try {
            Blog blog = blogMapper.selectById(blogId);
            if (blog == null || blog.getShopId() == null) {
                return;
            }
            shopAICacheInvalidationService.clearShopRelatedCaches(blog.getShopId());
            if (indexReview && shopReviewVectorIndexService != null) {
                shopReviewVectorIndexService.indexBlog(blog);
            }
            log.debug("Cleared shop AI caches after blog {}, blogId={}, shopId={}",
                    reason, blogId, blog.getShopId());
        } catch (RuntimeException e) {
            log.warn("Clear shop AI caches after blog event failed, reason={}, blogId={}", reason, blogId, e);
        }
    }
}
