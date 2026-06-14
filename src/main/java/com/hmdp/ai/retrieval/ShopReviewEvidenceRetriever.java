package com.hmdp.ai.retrieval;

import com.hmdp.ai.port.ReviewDataPort;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.EvidenceType;
import com.hmdp.dto.ai.ReviewDoc;
import com.hmdp.ai.infra.AiLogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private ReviewDataPort reviewDataPort;

    @Resource
    private ShopReviewVectorIndexService vectorIndexService;

    @Resource
    private EvidenceReranker evidenceReranker;

    @Value("${rag.review.enabled:true}")
    private boolean reviewRagEnabled;

    public List<EvidenceItem> retrieve(Long shopId, String query, String aspect, Integer limit) {
        if (shopId == null || shopId <= 0) {
            return new ArrayList<>();
        }
        int safeLimit = normalizeLimit(limit, DEFAULT_LIMIT);
        Map<String, EvidenceItem> candidates = new LinkedHashMap<>();

        addCandidates(candidates, reviewDataPort.findQualityReviews(shopId, 0, safeLimit), "规则召回:高赞评价", query, aspect);
        addCandidates(candidates, reviewDataPort.findRecentReviews(shopId, safeLimit), "规则召回:近期评价", query, aspect);
        addCandidates(candidates, reviewDataPort.findNegativeCandidateReviews(shopId, Math.max(3, safeLimit / 2)),
                "规则召回:负面候选", query, aspect);
        if (reviewRagEnabled && vectorIndexService != null) {
            mergeVectorEvidence(candidates, vectorIndexService.search(shopId, query, aspect, safeLimit), query, aspect);
        }

        List<EvidenceItem> result = evidenceReranker == null
                ? candidates.values().stream()
                .sorted(Comparator.comparing(EvidenceItem::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(EvidenceItem::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .collect(Collectors.toList())
                : evidenceReranker.rerank(new ArrayList<>(candidates.values()), query, aspect, safeLimit);

        log.debug("Retrieved {} evidence items for shopId={}, query={}",
                result.size(), shopId, AiLogSanitizer.safe(query));
        return result;
    }

    private void mergeVectorEvidence(Map<String, EvidenceItem> target,
                                     List<EvidenceItem> vectorEvidence,
                                     String query,
                                     String aspect) {
        if (vectorEvidence == null) {
            return;
        }
        for (EvidenceItem evidence : vectorEvidence) {
            if (evidence == null || evidence.getId() == null) {
                continue;
            }
            EvidenceItem existing = target.get(evidence.getId());
            if (existing == null) {
                target.put(evidence.getId(), evidence);
                continue;
            }
            double mergedScore = Math.max(existing.getScore() == null ? 0 : existing.getScore(),
                    evidence.getScore() == null ? 0 : evidence.getScore()) + 0.08;
            existing.setScore(Math.min(1.0, mergedScore));
            existing.setMatchedReason("规则+向量召回");
            if (containsAny(existing.getSnippet(), aspect, query)) {
                existing.setMatchedReason("规则+向量召回+问题相关");
            }
        }
    }

    private void addCandidates(Map<String, EvidenceItem> target,
                               List<ReviewDoc> reviews,
                               String reason,
                               String query,
                               String aspect) {
        if (reviews == null) {
            return;
        }
        for (ReviewDoc review : reviews) {
            if (review == null || review.getId() == null || review.getContent() == null) {
                continue;
            }
            EvidenceItem evidence = toEvidence(review, reason, query, aspect);
            EvidenceItem existing = target.get(evidence.getId());
            if (existing == null || evidence.getScore() > existing.getScore()) {
                target.put(evidence.getId(), evidence);
            }
        }
    }

    private EvidenceItem toEvidence(ReviewDoc review, String reason, String query, String aspect) {
        double score = 0.3;
        int liked = review.getLiked() == null ? 0 : review.getLiked();
        score += Math.min(0.35, liked / 100.0);
        String content = review.getContent();
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

        return EvidenceItem.builder()
                .id(EvidenceItem.reviewId(review.getId()))
                .type(EvidenceType.REVIEW)
                .sourceId(review.getId())
                .shopId(review.getShopId())
                .title("用户评价#" + review.getId())
                .snippet(AiLogSanitizer.safe(content, SNIPPET_LIMIT))
                .liked(liked)
                .createdAt(review.getCreateTime())
                .matchedReason(matchedReason)
                .score(Math.min(1.0, score))
                .build();
    }

    private boolean containsAny(String content, String aspect, String query) {
        if (content == null) {
            return false;
        }
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
        String normalized = text.replaceAll("[，。！？、,.;:：\\s]+", " ");
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
