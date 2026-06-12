package com.hmdp.service;

import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.entity.Blog;
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

    public TextSegment toSegment(Blog blog) {
        if (blog == null || blog.getId() == null || blog.getShopId() == null || isBlank(blog.getContent())) {
            return null;
        }
        Metadata metadata = new Metadata()
                .put(META_EVIDENCE_ID, EvidenceItem.reviewId(blog.getId()))
                .put(META_BLOG_ID, blog.getId())
                .put(META_SHOP_ID, blog.getShopId())
                .put(META_CONTENT_HASH, contentHash(blog))
                .put(META_CREATED_AT, blog.getCreateTime() == null ? "" : blog.getCreateTime().toString());
        return TextSegment.from(vectorText(blog), metadata);
    }

    public String contentHash(Blog blog) {
        if (blog == null) {
            return "";
        }
        return sha256((blog.getId() == null ? "" : blog.getId()) + "|"
                + (blog.getShopId() == null ? "" : blog.getShopId()) + "|"
                + (blog.getContent() == null ? "" : blog.getContent()));
    }

    private String vectorText(Blog blog) {
        StringBuilder builder = new StringBuilder();
        builder.append("店铺评价\n")
                .append("shopId: ").append(blog.getShopId()).append("\n")
                .append("blogId: ").append(blog.getId()).append("\n");
        if (!isBlank(blog.getTitle())) {
            builder.append("title: ").append(limit(blog.getTitle(), 120)).append("\n");
        }
        builder.append("content: ").append(limit(blog.getContent(), 500));
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
