package com.hmdp.dto;

import lombok.Data;

@Data
public class ShopGeoRebuildResult {

    private Long typeId;

    private Integer rebuiltTypes = 0;

    private Integer deletedKeys = 0;

    private Integer totalShops = 0;

    private Integer indexedShops = 0;

    private Integer skippedShops = 0;
}
