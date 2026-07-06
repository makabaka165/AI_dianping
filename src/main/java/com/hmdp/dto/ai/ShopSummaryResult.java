package com.hmdp.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hmdp.dto.ai.EvidenceItem;
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

    private List<EvidenceItem> evidence;

    private Double confidence;

    private Boolean degraded;

    private Boolean cacheHit;

    private String traceId;

    @JsonIgnore
    private String memoryId;

    private String promptVersion;

    private String modelName;

    private String fallbackReason;

    public ShopSummaryResult copy() {
        return ShopSummaryResult.builder()
                .shopId(shopId)
                .shopName(shopName)
                .coreSummary(coreSummary)
                .totalBlogs(totalBlogs)
                .avgRating(avgRating)
                .keyPoints(keyPoints)
                .overallSentiment(overallSentiment)
                .summaryTime(summaryTime)
                .evidence(evidence)
                .confidence(confidence)
                .degraded(degraded)
                .cacheHit(cacheHit)
                .traceId(traceId)
                .memoryId(memoryId)
                .promptVersion(promptVersion)
                .modelName(modelName)
                .fallbackReason(fallbackReason)
                .build();
    }

    public ShopSummaryResult withoutRequestMetadata() {
        ShopSummaryResult copied = copy();
        copied.setTraceId(null);
        copied.setMemoryId(null);
        copied.setPromptVersion(null);
        copied.setModelName(null);
        copied.setFallbackReason(null);
        copied.setCacheHit(false);
        return copied;
    }
}
