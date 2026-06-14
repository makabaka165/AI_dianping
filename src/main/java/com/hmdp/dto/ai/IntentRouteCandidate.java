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
public class IntentRouteCandidate {
    private ShopAIIntent intent;
    private Long shopId;
    private Long shopId1;
    private Long shopId2;
    private String aspect;
    private String userPreference;
    private String category;
    private Integer limit;
    private double confidence;
    private IntentRouteSource source;
    private List<String> missingParams;
    private String clarification;

    public List<String> safeMissingParams() {
        return missingParams == null ? new ArrayList<>() : missingParams;
    }

    public IntentRoutingResult toRoutingResult() {
        return IntentRoutingResult.builder()
                .intent(intent)
                .source(source)
                .shopId(shopId)
                .shopId1(shopId1)
                .shopId2(shopId2)
                .aspect(aspect)
                .userPreference(userPreference)
                .category(category)
                .limit(limit)
                .confidence(confidence)
                .missingParams(safeMissingParams())
                .clarification(clarification)
                .build();
    }
}
