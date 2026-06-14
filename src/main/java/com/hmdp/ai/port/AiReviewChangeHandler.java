package com.hmdp.ai.port;

public interface AiReviewChangeHandler {

    void onReviewPublished(Long reviewId);

    void onReviewLikeChanged(Long reviewId);
}
