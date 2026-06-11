package com.hmdp.dto.ai;

import lombok.Data;

import javax.validation.constraints.Size;

@Data
public class ShopChatRequest {
    private String sessionId = "default";

    @Size(max = 1000, message = "消息长度不能超过1000字")
    private String message;

    private Long shopId;
}
