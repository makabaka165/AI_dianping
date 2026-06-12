package com.hmdp.dto.ai;

import com.hmdp.entity.Shop;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopProfileSnapshot {
    private Long shopId;
    private String name;
    private Long typeId;
    private String area;
    private Long avgPrice;
    private Integer sold;
    private Integer comments;
    private Integer score;
    private String openHours;

    public static ShopProfileSnapshot from(Shop shop) {
        if (shop == null) {
            return null;
        }
        return ShopProfileSnapshot.builder()
                .shopId(shop.getId())
                .name(shop.getName())
                .typeId(shop.getTypeId())
                .area(shop.getArea())
                .avgPrice(shop.getAvgPrice())
                .sold(shop.getSold())
                .comments(shop.getComments())
                .score(shop.getScore())
                .openHours(shop.getOpenHours())
                .build();
    }
}
