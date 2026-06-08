package com.hmdp.dto;

import lombok.Data;

@Data
public class MerchantShopBindDTO {
    private Long merchantUserId;
    private Long shopId;
    private String remark;
}
