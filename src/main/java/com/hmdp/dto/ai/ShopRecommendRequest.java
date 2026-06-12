package com.hmdp.dto.ai;

import lombok.Data;

import javax.validation.constraints.Size;

@Data
public class ShopRecommendRequest {
    @Size(max = 500, message = "偏好描述不能超过500字")
    private String userPreference;

    @Size(max = 50, message = "分类长度不能超过50字")
    private String category;

    private Integer limit = 5;

    @Size(max = 64, message = "会话ID长度不能超过64字")
    private String sessionId = "default";
}
