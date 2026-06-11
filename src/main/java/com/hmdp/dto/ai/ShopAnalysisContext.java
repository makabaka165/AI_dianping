package com.hmdp.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopAnalysisContext {
    private Long shopId;
    private String shopName;
    private Integer totalReviews;
    private LocalDateTime latestReviewTime;
    private String contextVersion;
    private List<ReviewEvidence> evidence;

    public List<ReviewEvidence> safeEvidence() {
        return evidence == null ? new ArrayList<>() : evidence;
    }
}
