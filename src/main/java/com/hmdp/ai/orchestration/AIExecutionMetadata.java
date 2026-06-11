package com.hmdp.ai.orchestration;

import com.hmdp.ai.intent.ShopAIIntent;
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
public class AIExecutionMetadata {
    private String traceId;
    private String memoryId;
    private ShopAIIntent intent;
    private String promptVersion;
    private String modelName;
    private boolean degraded;
    private boolean cacheHit;
    private List<String> usedTools;
    private String fallbackReason;

    public List<String> safeUsedTools() {
        return usedTools == null ? new ArrayList<>() : usedTools;
    }
}
