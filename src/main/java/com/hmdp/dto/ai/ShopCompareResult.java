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
public class ShopCompareResult {
    public static final String SHOP_1 = "SHOP_1";
    public static final String SHOP_2 = "SHOP_2";
    public static final String TIE = "TIE";
    public static final String INSUFFICIENT = "INSUFFICIENT";

    private Long shopId1;
    private Long shopId2;
    private String aspect;
    private String conclusion;
    private String winnerByAspect;
    private Integer shop1Score;
    private Integer shop2Score;
    private List<String> shop1Pros;
    private List<String> shop2Pros;
    private List<String> riskNotes;
    private List<String> evidenceIds;

    public List<String> safeEvidenceIds() {
        return evidenceIds == null ? new ArrayList<>() : evidenceIds;
    }
}
