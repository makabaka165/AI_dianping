package com.hmdp.dto.ai;

import lombok.Data;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class ShopCompareRequest {
    private Long shopId1;
    private Long shopId2;

    @Size(max = 100, message = "对比维度长度不能超过100字")
    private String aspect;

    @Size(max = 64, message = "会话ID长度不能超过64字")
    @Pattern(regexp = "^[a-zA-Z0-9_.-]{0,64}$", message = "会话ID只能包含字母、数字、下划线、点和短横线")
    private String sessionId = "default";
}
