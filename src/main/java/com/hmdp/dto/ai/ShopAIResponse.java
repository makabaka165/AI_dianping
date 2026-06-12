package com.hmdp.dto.ai;

import com.hmdp.ai.intent.IntentRouteSource;
import com.hmdp.ai.intent.ShopAIIntent;
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
    private String response;
    private String answer;
    private String comparison;
    private String recommendations;
    private Long shopId;
    private String sessionId;
    private String memoryId;
    private String traceId;
    private ShopAIIntent intent;
    private IntentRouteSource routingSource;
    private Double routingConfidence;
    private List<String> usedTools;
    private List<ReviewEvidence> evidence;
    private Double confidence;
    private Boolean degraded;
    private Boolean cacheHit;
    private String winnerByAspect;
    private ShopAIAnalysisResult analysis;
}
