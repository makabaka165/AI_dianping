package com.hmdp.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ShopSummaryResult {
    private Long shopId;

    private String shopName;

    private String coreSummary;  // 核心总结

    private Integer totalBlogs;  // 总博客数

    private Double avgRating;    // 平均评分(基于点赞数计算)

    private List<String> keyPoints;  // 关键点

    private String overallSentiment; // 整体情感倾向

    private LocalDateTime summaryTime; // 总结时间
}

