package com.hmdp.dto.ai;

import lombok.Data;

@Data
public class ShopCompareRequest {
    private Long shopId1;
    private Long shopId2;
    private String aspect;
    private String sessionId = "default";
}
