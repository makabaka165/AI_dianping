package com.hmdp.ai.retrieval;

import com.hmdp.ai.infra.DocumentIndexDecision;
import com.hmdp.ai.infra.DocumentQualityAssessment;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ReviewDoc;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class ReviewVectorDocumentFactory {

    public static final String META_EVIDENCE_ID = "evidenceId";
    public static final String META_BLOG_ID = "blogId";
    public static final String META_SHOP_ID = "shopId";
    public static final String META_CONTENT_HASH = "contentHash";
    public static final String META_CREATED_AT = "createdAt";
    public static final String META_QUALITY_PROFILE = "qualityProfile";
    public static final String META_QUALITY_SCORE = "qualityScore";
    public static final String META_QUALITY_LEVEL = "qualityLevel";
    public static final String META_QUALITY_ISSUES = "qualityIssues";
    public static final String META_INDEX_DECISION_POLICY = "indexDecisionPolicy";
    public static final String META_INDEX_DECISION_ACTION = "indexDecisionAction";
    public static final String META_INDEX_DECISION_REASON = "indexDecisionReason";

    public TextSegment toSegment(ReviewDoc review) {
        return toSegment(review, null);
    }

    public TextSegment toSegment(ReviewDoc review, DocumentIndexDecision decision) {
        if (review == null || review.getId() == null || review.getShopId() == null || isBlank(review.getContent())) {
            return null;
        }
        Metadata metadata = new Metadata()
                .put(META_EVIDENCE_ID, EvidenceItem.reviewId(review.getId()))
                .put(META_BLOG_ID, review.getId())
                .put(META_SHOP_ID, review.getShopId())
                .put(META_CONTENT_HASH, contentHash(review))
                .put(META_CREATED_AT, review.getCreateTime() == null ? "" : review.getCreateTime().toString());
        addQualityMetadata(metadata, decision);
        return TextSegment.from(vectorText(review), metadata);
    }

    private void addQualityMetadata(Metadata metadata, DocumentIndexDecision decision) {
        if (metadata == null || decision == null || decision.getAssessment() == null) {
            return;
        }
        DocumentQualityAssessment assessment = decision.getAssessment();
        metadata.put(META_QUALITY_PROFILE, assessment.getProfile().name())
                .put(META_QUALITY_SCORE, assessment.getScore())
                .put(META_QUALITY_LEVEL, assessment.getLevel().name())
                .put(META_QUALITY_ISSUES, String.join(",", assessment.getIssues()))
                .put(META_INDEX_DECISION_POLICY, decision.getPolicy().name())
                .put(META_INDEX_DECISION_ACTION, decision.getAction().name())
                .put(META_INDEX_DECISION_REASON, decision.getReason());
    }

    public String contentHash(ReviewDoc review) {
        if (review == null) {
            return "";
        }
        return sha256((review.getId() == null ? "" : review.getId()) + "|"
                + (review.getShopId() == null ? "" : review.getShopId()) + "|"
                + (review.getContent() == null ? "" : review.getContent()));
    }

    private String vectorText(ReviewDoc review) {
        StringBuilder builder = new StringBuilder();
        builder.append("店铺评价\n")
                .append("shopId: ").append(review.getShopId()).append("\n")
                .append("blogId: ").append(review.getId()).append("\n");
        if (!isBlank(review.getTitle())) {
            builder.append("title: ").append(limit(review.getTitle(), 120)).append("\n");
        }
        builder.append("content: ").append(limit(review.getContent(), 500));
        return builder.toString();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
