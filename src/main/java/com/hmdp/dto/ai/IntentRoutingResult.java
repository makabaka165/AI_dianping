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
public class IntentRoutingResult {
    private ShopAIIntent intent;
    private IntentRouteSource source;
    private Long shopId;
    private Long shopId1;
    private Long shopId2;
    private String aspect;
    private String userPreference;
    private String category;
    private Integer limit;
    private double confidence;
    private List<String> missingParams;
    private String clarification;
}
