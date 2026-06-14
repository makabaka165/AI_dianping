package com.hmdp.ai.retrieval;

import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.EvidenceType;
import com.hmdp.dto.ai.ReviewDoc;
import com.hmdp.ai.port.ReviewDataPort;
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
    private ReviewDataPort reviewDataPort;

    @Mock
    private ShopReviewVectorIndexService vectorIndexService;

    private ShopReviewEvidenceRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new ShopReviewEvidenceRetriever();
        ReflectionTestUtils.setField(retriever, "reviewDataPort", reviewDataPort);
        ReflectionTestUtils.setField(retriever, "vectorIndexService", vectorIndexService);
        ReflectionTestUtils.setField(retriever, "evidenceReranker", new EvidenceReranker());
        ReflectionTestUtils.setField(retriever, "reviewRagEnabled", false);
    }

    @Test
    void retrieveShouldMergeAndRankEvidence() {
        ReviewDoc highLiked = review(1L, "服务很好，环境也很好，适合聚餐", 80);
        ReviewDoc recent = review(2L, "最近去过，出餐有点慢但味道不错", 5);
        ReviewDoc negative = review(3L, "服务慢，价格有点贵", 3);

        when(reviewDataPort.findQualityReviews(eq(10L), eq(0), eq(5))).thenReturn(Arrays.asList(highLiked, recent));
        when(reviewDataPort.findRecentReviews(eq(10L), eq(5))).thenReturn(Collections.singletonList(recent));
        when(reviewDataPort.findNegativeCandidateReviews(eq(10L), eq(3))).thenReturn(Collections.singletonList(negative));

        List<EvidenceItem> evidence = retriever.retrieve(10L, "服务", null, 5);

        assertThat(evidence).hasSize(3);
        assertThat(evidence.get(0).getId()).isEqualTo("review:1");
        assertThat(evidence.get(0).getType()).isEqualTo(EvidenceType.REVIEW);
        assertThat(evidence).extracting(EvidenceItem::getId).containsExactly("review:1", "review:3", "review:2");
    }

    @Test
    void retrieveShouldMergeVectorEvidenceWhenReviewRagEnabled() {
        ReflectionTestUtils.setField(retriever, "reviewRagEnabled", true);
        ReviewDoc highLiked = review(1L, "服务很好，环境也很好，适合聚餐", 80);
        EvidenceItem vectorOnly = EvidenceItem.builder()
                .id("review:4")
                .type(EvidenceType.REVIEW)
                .sourceId(4L)
                .shopId(10L)
                .snippet("服务细致，适合家庭聚餐")
                .matchedReason("向量召回")
                .score(0.7)
                .liked(6)
                .createdAt(LocalDateTime.now())
                .build();

        when(reviewDataPort.findQualityReviews(eq(10L), eq(0), eq(5))).thenReturn(Collections.singletonList(highLiked));
        when(reviewDataPort.findRecentReviews(eq(10L), eq(5))).thenReturn(Collections.emptyList());
        when(reviewDataPort.findNegativeCandidateReviews(eq(10L), eq(3))).thenReturn(Collections.emptyList());
        when(vectorIndexService.search(eq(10L), eq("服务"), eq(null), eq(5))).thenReturn(Collections.singletonList(vectorOnly));

        List<EvidenceItem> evidence = retriever.retrieve(10L, "服务", null, 5);

        assertThat(evidence).extracting(EvidenceItem::getId).contains("review:1", "review:4");
        assertThat(evidence).filteredOn(item -> "review:4".equals(item.getId()))
                .extracting(EvidenceItem::getMatchedReason)
                .containsExactly("向量召回");
    }

    private ReviewDoc review(Long id, String content, Integer liked) {
        return ReviewDoc.builder()
                .id(id)
                .shopId(10L)
                .content(content)
                .liked(liked)
                .createTime(LocalDateTime.now().minusDays(id))
                .build();
    }
}
