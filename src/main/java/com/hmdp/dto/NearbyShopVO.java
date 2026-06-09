package com.hmdp.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class NearbyShopVO extends ShopDetailVO {

    private Double distance;
}
