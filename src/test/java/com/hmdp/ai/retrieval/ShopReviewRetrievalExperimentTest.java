package com.hmdp.ai.retrieval;

import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.infra.DocumentIndexDecisionService;
import com.hmdp.ai.infra.DocumentQualityAssessor;
import com.hmdp.ai.port.ReviewDataPort;
import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.ReviewDoc;
import com.hmdp.dto.ai.ReviewVersion;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ShopReviewRetrievalExperimentTest {

    private final InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
    private final DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
    private final InMemoryReviewDataPort reviewDataPort = new InMemoryReviewDataPort();

    private ShopReviewVectorIndexService service;

    @BeforeEach
    void setUp() {
        service = new ShopReviewVectorIndexService(provider(embeddingStore), provider(embeddingModel));
        ReflectionTestUtils.setField(service, "reviewDataPort", reviewDataPort);
        ReflectionTestUtils.setField(service, "documentFactory", new ReviewVectorDocumentFactory());
        ReflectionTestUtils.setField(service, "documentQualityAssessor", new DocumentQualityAssessor());
        ReflectionTestUtils.setField(service, "documentIndexDecisionService", new DocumentIndexDecisionService());
        ReflectionTestUtils.setField(service, "aiMetricsService", mock(AiMetricsService.class));
        ReflectionTestUtils.setField(service, "ragEnabled", true);
        ReflectionTestUtils.setField(service, "reviewRagEnabled", true);
        ReflectionTestUtils.setField(service, "minScore", 0.55);
        ReflectionTestUtils.setField(service, "maxVectorCandidates", 20);
        ReflectionTestUtils.setField(service, "backfillPageSize", 200);
        ReflectionTestUtils.setField(service, "compactEnabled", true);
        ReflectionTestUtils.setField(service, "reviewQualityEnabled", true);
        ReflectionTestUtils.setField(service, "reviewQualityIndexPolicy", "observe_only");
        ReflectionTestUtils.setField(service, "reviewQualityMinScore", 0.45);
    }

    @Test
    void serviceAndTasteQuestionShouldRetrieveSameShopUsefulReviews() {
        ReviewDoc serviceReview = review(1L, 10L,
                "周末和朋友来吃晚餐，服务态度不错，店员会主动加水。招牌菜味道稳定，牛肉分量足，人均 88 元。");
        ReviewDoc environmentReview = review(2L, 10L,
                "店里环境干净，卫生情况不错，但高峰期有点吵，适合同事聚餐。");
        ReviewDoc otherShopReview = review(3L, 20L,
                "服务很好，味道也不错，人均 60 元。");
        ReviewDoc spamReview = review(4L, 10L,
                "加微信返现，扫码领优惠代理，http://promo.example.com，复制这条评价有红包。");
        reviewDataPort.save(serviceReview, environmentReview, otherShopReview, spamReview);

        assertThat(index(serviceReview).getIndexed()).isEqualTo(1);
        assertThat(index(environmentReview).getIndexed()).isEqualTo(1);
        assertThat(index(otherShopReview).getIndexed()).isEqualTo(1);
        assertThat(index(spamReview).getIndexed()).isEqualTo(1);

        List<EvidenceItem> evidence = service.search(10L, "服务态度和招牌菜味道怎么样？", "服务 味道", 5);

        assertThat(evidence).extracting(EvidenceItem::getId)
                .contains("review:1")
                .doesNotContain("review:3", "review:4");
        assertThat(evidence.get(0).getId()).isEqualTo("review:1");
        assertThat(evidence.get(0).getSnippet()).contains("服务态度不错", "招牌菜味道稳定");
    }

    @Test
    void observeOnlyShouldIndexLowQualityReviewWithoutMakingItWinUnrelatedRetrieval() {
        ReviewDoc usefulReview = review(11L, 10L,
                "午餐点了双人套餐，上菜大约 15 分钟。味道不错，服务也算及时，人均 60 元。");
        ReviewDoc vagueReview = review(12L, 10L, "挺好的，还不错，整体可以，下次再说。");
        reviewDataPort.save(usefulReview, vagueReview);

        assertThat(index(usefulReview).getIndexed()).isEqualTo(1);
        ShopRagRebuildResult vagueIndexResult = index(vagueReview);

        assertThat(vagueIndexResult.getIndexed()).isEqualTo(1);
        List<EvidenceItem> evidence = service.search(10L, "上菜速度和套餐味道怎么样？", "味道 上菜 套餐", 5);

        assertThat(evidence).extracting(EvidenceItem::getId)
                .contains("review:11")
                .doesNotContain("review:12");
    }

    private ShopRagRebuildResult index(ReviewDoc review) {
        return service.indexBlog(review.getId());
    }

    private ReviewDoc review(Long id, Long shopId, String content) {
        return ReviewDoc.builder()
                .id(id)
                .shopId(shopId)
                .content(content)
                .liked(5)
                .status(1)
                .deleted(0)
                .createTime(LocalDateTime.of(2026, 1, 1, 12, 0).minusDays(id))
                .build();
    }

    private <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<T>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static class InMemoryReviewDataPort implements ReviewDataPort {
        private final Map<Long, ReviewDoc> reviews = new LinkedHashMap<>();

        void save(ReviewDoc... docs) {
            for (ReviewDoc doc : docs) {
                reviews.put(doc.getId(), doc);
            }
        }

        @Override
        public ReviewDoc getReview(Long reviewId) {
            return reviews.get(reviewId);
        }

        @Override
        public ReviewVersion getReviewVersion(Long shopId) {
            return null;
        }

        @Override
        public List<ReviewDoc> findQualityReviews(Long shopId, int minLiked, int limit) {
            return findReviewsByShopId(shopId).stream()
                    .filter(review -> review.getLiked() != null && review.getLiked() >= minLiked)
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        @Override
        public List<ReviewDoc> findRecentReviews(Long shopId, int limit) {
            return findReviewsByShopId(shopId).stream()
                    .sorted(Comparator.comparing(ReviewDoc::getCreateTime).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        @Override
        public List<ReviewDoc> findNegativeCandidateReviews(Long shopId, int limit) {
            return new ArrayList<>();
        }

        @Override
        public List<ReviewDoc> findActiveReviewsForRag(Long shopId, int limit) {
            return findReviewsByShopId(shopId).stream()
                    .filter(review -> review.getStatus() != null && review.getStatus() == 1)
                    .filter(review -> review.getDeleted() != null && review.getDeleted() == 0)
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        @Override
        public List<Long> findActiveShopIdsForRag(int limit) {
            return reviews.values().stream()
                    .map(ReviewDoc::getShopId)
                    .distinct()
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        @Override
        public List<ReviewDoc> findReviewsByShopId(Long shopId) {
            return reviews.values().stream()
                    .filter(review -> review.getShopId().equals(shopId))
                    .collect(Collectors.toList());
        }
    }
}
