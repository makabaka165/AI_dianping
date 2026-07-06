package com.hmdp.dto.ai;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopAIResponse {
    private String sessionId;
    @JsonIgnore
    private String memoryId;
    private String traceId;
    private ShopAIIntent intent;
    private IntentRouteSource routingSource;
    private Double routingConfidence;
    private String promptVersion;
    private List<EvidenceItem> evidence;
    private Double confidence;
    private Boolean degraded;
    private Boolean cacheHit;
    private String fallbackReason;
    private com.hmdp.dto.ai.ShopSummaryResult summary;
    private ShopQAResult qa;
    private ShopCompareResult compare;
    private ShopRecommendResult recommend;
    private ShopChatResult chat;
}
