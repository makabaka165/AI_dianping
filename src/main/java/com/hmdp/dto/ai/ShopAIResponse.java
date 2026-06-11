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
public class ShopAIResponse {
    private String response;
    private String answer;
    private String comparison;
    private String recommendations;
    private Long shopId;
    private String sessionId;
    private String memoryId;
    private String traceId;
    private List<String> usedTools;
    private List<ReviewEvidence> evidence;
    private Double confidence;
    private Boolean degraded;
    private Boolean cacheHit;
    private String winnerByAspect;
    private ShopAIAnalysisResult analysis;
}
