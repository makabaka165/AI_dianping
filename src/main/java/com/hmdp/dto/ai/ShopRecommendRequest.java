package com.hmdp.dto.ai;

import lombok.Data;

import javax.validation.constraints.Size;

@Data
public class ShopRecommendRequest {
    @Size(max = 500, message = "偏好描述不能超过500字")
    private String userPreference;

    private String category;
    private Integer limit = 5;
    private String sessionId = "default";
}
