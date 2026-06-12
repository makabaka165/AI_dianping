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
public class ShopAIStreamEvent {
    private String type;
    private String text;
    private String message;
    private String traceId;
    private String sessionId;
    private String memoryId;
    private String promptVersion;
    private ShopAIIntent intent;
    private IntentRouteSource routingSource;
    private Double routingConfidence;
    private List<EvidenceItem> evidence;
    private Double confidence;
    private Boolean degraded;
    private Boolean cacheHit;
}
