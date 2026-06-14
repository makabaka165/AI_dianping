package com.hmdp.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentSlotState {
    private String userId;
    private String sessionId;
    private ShopAIIntent intent;
    private Long shopId;
    private Long shopId1;
    private Long shopId2;
    private String aspect;
    private String userPreference;
    private String category;
    private Integer limit;
    private Long updatedAtEpochMillis;
    private ShopAIIntent pendingIntent;
    private Long pendingShopId;
    private Long pendingShopId1;
    private Long pendingShopId2;
    private String pendingAspect;
    private String pendingUserPreference;
    private String pendingCategory;
    private Integer pendingLimit;
    private List<String> missingFields;
    private Long pendingUpdatedAtEpochMillis;
}
