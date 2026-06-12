package com.hmdp.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceItem {
    private String id;
    private EvidenceType type;
    private Long shopId;
    private Long sourceId;
    private String title;
    private String snippet;
    private Integer liked;
    private LocalDateTime createdAt;
    private String matchedReason;
    private Double score;
    private ShopProfileSnapshot shopProfile;

    public static String reviewId(Long blogId) {
        return blogId == null ? null : "review:" + blogId;
    }

    public static String shopProfileId(Long shopId) {
        return shopId == null ? null : "shop_profile:" + shopId;
    }
}
