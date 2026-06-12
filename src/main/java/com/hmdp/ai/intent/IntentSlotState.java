package com.hmdp.ai.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
