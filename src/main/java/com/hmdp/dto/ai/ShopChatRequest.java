package com.hmdp.dto.ai;

import lombok.Data;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class ShopChatRequest {
    @Size(max = 64, message = "会话ID长度不能超过64字")
    @Pattern(regexp = "^[a-zA-Z0-9_.-]{0,64}$", message = "会话ID只能包含字母、数字、下划线、点和短横线")
    private String sessionId = "default";

    @Size(max = 1000, message = "消息长度不能超过1000字")
    private String message;

    private Long shopId;
}
