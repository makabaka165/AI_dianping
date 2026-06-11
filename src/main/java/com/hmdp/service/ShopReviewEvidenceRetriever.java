package com.hmdp.service;

import com.hmdp.dto.ai.ReviewEvidence;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.utils.AiLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ShopReviewEvidenceRetriever {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 10;
    private static final int SNIPPET_LIMIT = 300;

    @Resource
    private BlogMapper blogMapper;

    public List<ReviewEvidence> retrieve(Long shopId, String query, String aspect, Integer limit) {
        if (shopId == null || shopId <= 0) {
            return new ArrayList<>();
        }
        int safeLimit = normalizeLimit(limit, DEFAULT_LIMIT);
        Map<Long, ReviewEvidence> candidates = new LinkedHashMap<>();

        addCandidates(candidates, blogMapper.selectQualityBlogsByShopId(shopId, 0, safeLimit), "高赞评价", query, aspect);
        addCandidates(candidates, blogMapper.selectRecentBlogsByShopId(shopId, safeLimit), "近期评价", query, aspect);
        addCandidates(candidates, blogMapper.selectNegativeCandidateBlogsByShopId(shopId, Math.max(3, safeLimit / 2)), "负向候选", query, aspect);

        List<ReviewEvidence> result = candidates.values().stream()
                .sorted(Comparator.comparing(ReviewEvidence::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ReviewEvidence::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .collect(Collectors.toList());

        log.debug("Retrieved {} review evidence items for shopId={}, query={}",
                result.size(), shopId, AiLogSanitizer.safe(query));
        return result;
    }

    private void addCandidates(Map<Long, ReviewEvidence> target,
                               List<Blog> blogs,
                               String reason,
                               String query,
                               String aspect) {
        if (blogs == null) {
            return;
        }
        for (Blog blog : blogs) {
            if (blog == null || blog.getId() == null || blog.getContent() == null) {
                continue;
            }
            ReviewEvidence evidence = toEvidence(blog, reason, query, aspect);
            ReviewEvidence existing = target.get(blog.getId());
            if (existing == null || evidence.getScore() > existing.getScore()) {
                target.put(blog.getId(), evidence);
            }
        }
    }

    private ReviewEvidence toEvidence(Blog blog, String reason, String query, String aspect) {
        double score = 0.3;
        int liked = blog.getLiked() == null ? 0 : blog.getLiked();
        score += Math.min(0.35, liked / 100.0);
        String content = blog.getContent();
        if (content.length() >= 80) {
            score += 0.1;
        }
        String matchedReason = reason;
        if (containsAny(content, aspect, query)) {
            score += 0.25;
            matchedReason = reason + "+问题相关";
        }
        if (containsNegative(content)) {
            score += 0.05;
        }

        return ReviewEvidence.builder()
                .blogId(blog.getId())
                .shopId(blog.getShopId())
                .snippet(AiLogSanitizer.safe(content, SNIPPET_LIMIT))
                .liked(liked)
                .createdAt(blog.getCreateTime())
                .matchedReason(matchedReason)
                .score(Math.min(1.0, score))
                .build();
    }

    private boolean containsAny(String content, String aspect, String query) {
        String lower = content.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        addTerms(terms, aspect);
        addTerms(terms, query);
        for (String term : terms) {
            if (!term.isEmpty() && lower.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void addTerms(List<String> terms, String text) {
        if (text == null) {
            return;
        }
        String normalized = text.replaceAll("[，。！？、,.;；:：\\s]+", " ");
        for (String part : normalized.split(" ")) {
            String term = part.trim();
            if (term.length() >= 2 && term.length() <= 12) {
                terms.add(term);
            }
        }
    }

    private boolean containsNegative(String content) {
        return content.contains("差") || content.contains("失望") || content.contains("不好")
                || content.contains("一般") || content.contains("贵") || content.contains("慢")
                || content.contains("坑");
    }

    private int normalizeLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(MAX_LIMIT, limit);
    }
}
