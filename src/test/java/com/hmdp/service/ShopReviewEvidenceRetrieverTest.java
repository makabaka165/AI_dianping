package com.hmdp.service;

import com.hmdp.dto.ai.ReviewEvidence;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopReviewEvidenceRetrieverTest {

    @Mock
    private BlogMapper blogMapper;

    private ShopReviewEvidenceRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new ShopReviewEvidenceRetriever();
        ReflectionTestUtils.setField(retriever, "blogMapper", blogMapper);
    }

    @Test
    void retrieveShouldMergeAndRankEvidence() {
        Blog highLiked = blog(1L, "服务很好，环境也很好，适合聚餐", 80);
        Blog recent = blog(2L, "最近去过，出餐有点慢但味道不错", 5);
        Blog negative = blog(3L, "服务慢，价格有点贵", 3);

        when(blogMapper.selectQualityBlogsByShopId(eq(10L), eq(0), eq(5))).thenReturn(Arrays.asList(highLiked, recent));
        when(blogMapper.selectRecentBlogsByShopId(eq(10L), eq(5))).thenReturn(Collections.singletonList(recent));
        when(blogMapper.selectNegativeCandidateBlogsByShopId(eq(10L), eq(3))).thenReturn(Collections.singletonList(negative));

        List<ReviewEvidence> evidence = retriever.retrieve(10L, "服务", null, 5);

        assertThat(evidence).hasSize(3);
        assertThat(evidence.get(0).getBlogId()).isEqualTo(1L);
        assertThat(evidence).extracting(ReviewEvidence::getBlogId).containsExactly(1L, 3L, 2L);
    }

    private Blog blog(Long id, String content, Integer liked) {
        return new Blog()
                .setId(id)
                .setShopId(10L)
                .setContent(content)
                .setLiked(liked)
                .setCreateTime(LocalDateTime.now().minusDays(id));
    }
}
