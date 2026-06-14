package com.hmdp.ai.port.adapter;

import com.hmdp.ai.port.ReviewDataPort;
import com.hmdp.dto.ai.ReviewDoc;
import com.hmdp.dto.ai.ReviewVersion;
import com.hmdp.mapper.BlogMapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MyBatisReviewDataAdapter implements ReviewDataPort {

    @Resource
    private BlogMapper blogMapper;

    @Override
    public ReviewDoc getReview(Long reviewId) {
        if (reviewId == null) {
            return null;
        }
        return toDoc(blogMapper.selectById(reviewId));
    }

    @Override
    public ReviewVersion getReviewVersion(Long shopId) {
        Map<String, Object> version = blogMapper.selectReviewVersionByShopId(shopId);
        return ReviewVersion.builder()
                .totalReviews(numberValue(version == null ? null : version.get("total_count")))
                .latestReviewTime(dateTimeValue(version == null ? null : version.get("latest_time")))
                .build();
    }

    @Override
    public List<ReviewDoc> findQualityReviews(Long shopId, int minLiked, int limit) {
        return toDocs(blogMapper.selectQualityBlogsByShopId(shopId, minLiked, limit));
    }

    @Override
    public List<ReviewDoc> findRecentReviews(Long shopId, int limit) {
        return toDocs(blogMapper.selectRecentBlogsByShopId(shopId, limit));
    }

    @Override
    public List<ReviewDoc> findNegativeCandidateReviews(Long shopId, int limit) {
        return toDocs(blogMapper.selectNegativeCandidateBlogsByShopId(shopId, limit));
    }

    @Override
    public List<ReviewDoc> findActiveReviewsForRag(Long shopId, int limit) {
        return toDocs(blogMapper.selectActiveBlogsByShopIdForRag(shopId, limit));
    }

    @Override
    public List<Long> findActiveShopIdsForRag(int limit) {
        return blogMapper.selectActiveShopIdsForRag(limit);
    }

    @Override
    public List<ReviewDoc> findReviewsByShopId(Long shopId) {
        return toDocs(blogMapper.selectBlogsByShopId(shopId));
    }

    private List<ReviewDoc> toDocs(List<com.hmdp.entity.Blog> blogs) {
        if (blogs == null) {
            return Collections.emptyList();
        }
        return blogs.stream()
                .map(this::toDoc)
                .collect(Collectors.toList());
    }

    private ReviewDoc toDoc(com.hmdp.entity.Blog blog) {
        if (blog == null) {
            return null;
        }
        return ReviewDoc.builder()
                .id(blog.getId())
                .shopId(blog.getShopId())
                .title(blog.getTitle())
                .content(blog.getContent())
                .liked(blog.getLiked())
                .createTime(blog.getCreateTime())
                .status(blog.getStatus())
                .deleted(blog.getDeleted())
                .build();
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
