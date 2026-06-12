package com.hmdp.dto.ai;

import lombok.Data;

import javax.validation.constraints.Size;

@Data
public class ShopCompareRequest {
    private Long shopId1;
    private Long shopId2;

    @Size(max = 100, message = "对比维度长度不能超过100字")
    private String aspect;

    @Size(max = 64, message = "会话ID长度不能超过64字")
    private String sessionId = "default";
}
