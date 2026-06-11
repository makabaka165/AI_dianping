package com.hmdp.dto.ai;

import lombok.Data;

import javax.validation.constraints.Size;

@Data
public class ShopAskRequest {
    @Size(max = 1000, message = "问题长度不能超过1000字")
    private String question;

    private String sessionId = "default";
}
