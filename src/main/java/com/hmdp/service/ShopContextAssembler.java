package com.hmdp.service;

import com.hmdp.dto.ai.ReviewEvidence;
import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.mapper.BlogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ShopContextAssembler {

    private static final int SINGLE_SHOP_EVIDENCE_LIMIT = 8;
    private static final int COMPARE_EVIDENCE_LIMIT = 5;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private ShopReviewEvidenceRetriever evidenceRetriever;

    public ShopAnalysisContext buildForShop(Long shopId, String query) {
        return buildForShop(shopId, query, null, SINGLE_SHOP_EVIDENCE_LIMIT);
    }

    public ShopAnalysisContext buildForCompare(Long shopId, String query, String aspect) {
        return buildForShop(shopId, query, aspect, COMPARE_EVIDENCE_LIMIT);
    }

    private ShopAnalysisContext buildForShop(Long shopId, String query, String aspect, int limit) {
        Map<String, Object> version = blogMapper.selectReviewVersionByShopId(shopId);
        int totalReviews = numberValue(version == null ? null : version.get("total_count"));
        LocalDateTime latestReviewTime = dateTimeValue(version == null ? null : version.get("latest_time"));
        List<ReviewEvidence> evidence = evidenceRetriever.retrieve(shopId, query, aspect, limit);

        return ShopAnalysisContext.builder()
                .shopId(shopId)
                .shopName("店铺" + shopId)
                .totalReviews(totalReviews)
                .latestReviewTime(latestReviewTime)
                .contextVersion(buildContextVersion(totalReviews, latestReviewTime))
                .evidence(evidence)
                .build();
    }

    public String toPromptBlock(ShopAnalysisContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("店铺ID: ").append(context.getShopId()).append("\n");
        prompt.append("店铺名称: ").append(context.getShopName()).append("\n");
        prompt.append("评价总数: ").append(context.getTotalReviews()).append("\n");
        prompt.append("上下文版本: ").append(context.getContextVersion()).append("\n");
        prompt.append("评价证据:\n");
        int index = 1;
        for (ReviewEvidence evidence : context.safeEvidence()) {
            prompt.append("[证据").append(index++).append(" blogId=").append(evidence.getBlogId()).append("] ")
                    .append("点赞=").append(evidence.getLiked()).append(", ")
                    .append("原因=").append(evidence.getMatchedReason()).append(", ")
                    .append("内容=").append(evidence.getSnippet()).append("\n");
        }
        if (context.safeEvidence().isEmpty()) {
            prompt.append("无可用评价证据。\n");
        }
        return prompt.toString();
    }

    private String buildContextVersion(int totalReviews, LocalDateTime latestReviewTime) {
        String latest = latestReviewTime == null ? "none" : latestReviewTime.toString();
        return totalReviews + ":" + latest;
    }

    private int numberValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private LocalDateTime dateTimeValue(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        return null;
    }
}
