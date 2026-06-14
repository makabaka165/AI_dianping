package com.hmdp.ai.port;

import com.hmdp.dto.ai.ReviewDoc;
import com.hmdp.dto.ai.ReviewVersion;

import java.util.List;

public interface ReviewDataPort {

    ReviewDoc getReview(Long reviewId);

    ReviewVersion getReviewVersion(Long shopId);

    List<ReviewDoc> findQualityReviews(Long shopId, int minLiked, int limit);

    List<ReviewDoc> findRecentReviews(Long shopId, int limit);

    List<ReviewDoc> findNegativeCandidateReviews(Long shopId, int limit);

    List<ReviewDoc> findActiveReviewsForRag(Long shopId, int limit);

    List<Long> findActiveShopIdsForRag(int limit);

    List<ReviewDoc> findReviewsByShopId(Long shopId);
}
