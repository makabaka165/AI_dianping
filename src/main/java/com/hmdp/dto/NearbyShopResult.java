package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class NearbyShopResult {
    private List<NearbyShopVO> list;
    private Double lastDistance;
    private Long lastId;
    private Boolean hasMore;
}
