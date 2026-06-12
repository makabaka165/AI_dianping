package com.hmdp.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopRecommendResult {
    private String userPreference;
    private String category;
    private List<ShopRecommendationItem> items;
    private String message;

    public List<ShopRecommendationItem> safeItems() {
        return items == null ? new ArrayList<>() : items;
    }
}
