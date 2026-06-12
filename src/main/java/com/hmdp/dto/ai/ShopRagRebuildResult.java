package com.hmdp.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopRagRebuildResult {
    private Long shopId;
    private Integer indexed;
    private Integer skipped;
    private Integer failed;
    private Long durationMs;
    private String message;

    public static ShopRagRebuildResult empty(Long shopId, long durationMs, String message) {
        return ShopRagRebuildResult.builder()
                .shopId(shopId)
                .indexed(0)
                .skipped(0)
                .failed(0)
                .durationMs(durationMs)
                .message(message)
                .build();
    }
}
