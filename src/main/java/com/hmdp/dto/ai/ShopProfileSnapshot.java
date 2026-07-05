package com.hmdp.dto.ai;
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
    private String address;
    private Long avgPrice;
    private Integer sold;
    private Integer comments;
    private Integer score;
    private String openHours;

    public static ShopProfileSnapshot from(ShopView shopView) {
        if (shopView == null) {
            return null;
        }
        return ShopProfileSnapshot.builder()
                .shopId(shopView.getId())
                .name(shopView.getName())
                .typeId(shopView.getTypeId())
                .area(shopView.getArea())
                .address(shopView.getAddress())
                .avgPrice(shopView.getAvgPrice())
                .sold(shopView.getSold())
                .comments(shopView.getComments())
                .score(shopView.getScore())
                .openHours(shopView.getOpenHours())
                .build();
    }
}
