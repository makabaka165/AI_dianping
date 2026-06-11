package com.hmdp.ai.orchestration;

import com.hmdp.ai.intent.ShopAIIntent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopAIRequestContext {
    private String userId;
    private String sessionId;
    private String memoryId;
    private String traceId;
    private String sourceEndpoint;
    private ShopAIIntent intent;
}
