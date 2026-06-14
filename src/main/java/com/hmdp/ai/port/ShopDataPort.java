package com.hmdp.ai.port;

import com.hmdp.dto.ai.ShopView;

import java.util.List;

public interface ShopDataPort {

    ShopView getShop(Long shopId);

    List<ShopView> findRecommendCandidates(String category, int limit);

    boolean shopExists(Long shopId);

    int getReviewCount(Long shopId);
}
