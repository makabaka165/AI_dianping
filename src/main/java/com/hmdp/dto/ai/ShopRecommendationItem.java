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
public class ShopRecommendationItem {
    private Integer rank;
    private Long shopId;
    private String shopName;
    private String address;
    private String reason;
    private String suitableFor;
    private String uncertainty;
    private List<String> evidenceIds;
    private Double confidence;

    public List<String> safeEvidenceIds() {
        return evidenceIds == null ? new ArrayList<>() : evidenceIds;
    }
}
