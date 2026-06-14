package com.hmdp.ai.application;

import com.hmdp.ai.port.AiReviewChangeHandler;
import com.hmdp.ai.port.ReviewDataPort;
import com.hmdp.dto.ai.ReviewDoc;
import com.hmdp.ai.retrieval.ShopReviewVectorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class DefaultAiReviewChangeHandler implements AiReviewChangeHandler {

    @Resource
    private ReviewDataPort reviewDataPort;

    @Resource
    private ShopAICacheInvalidationService shopAICacheInvalidationService;

    @Resource
    private ShopReviewVectorIndexService shopReviewVectorIndexService;

    @Override
    public void onReviewPublished(Long reviewId) {
        clearByReviewId(reviewId, "publish", true);
    }

    @Override
    public void onReviewLikeChanged(Long reviewId) {
        clearByReviewId(reviewId, "like", false);
    }

    private void clearByReviewId(Long reviewId, String reason, boolean indexReview) {
        if (reviewId == null || reviewId <= 0) {
            return;
        }
        try {
            ReviewDoc review = reviewDataPort.getReview(reviewId);
            if (review == null || review.getShopId() == null) {
                return;
            }
            shopAICacheInvalidationService.clearShopRelatedCaches(review.getShopId());
            if (indexReview && shopReviewVectorIndexService != null) {
                shopReviewVectorIndexService.indexBlog(review);
            }
            log.debug("Cleared shop AI caches after blog {}, blogId={}, shopId={}",
                    reason, reviewId, review.getShopId());
        } catch (RuntimeException e) {
            log.warn("Clear shop AI caches after blog event failed, reason={}, blogId={}", reason, reviewId, e);
        }
    }
}
