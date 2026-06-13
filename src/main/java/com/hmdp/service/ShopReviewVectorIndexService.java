package com.hmdp.service;

import com.hmdp.dto.ai.EvidenceItem;
import com.hmdp.dto.ai.EvidenceType;
import com.hmdp.dto.ai.ShopRagRebuildResult;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.utils.AiLogSanitizer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ShopReviewVectorIndexService {

    private static final int BLOG_STATUS_PUBLISHED = 1;
    private static final int BLOG_NOT_DELETED = 0;
    private static final int SNIPPET_LIMIT = 300;

    private final ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private ReviewVectorDocumentFactory documentFactory;

    @Resource
    private AiMetricsService aiMetricsService;

    @Value("${rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${rag.review.enabled:true}")
    private boolean reviewRagEnabled;

    @Value("${rag.review.min-score:0.55}")
    private double minScore;

    @Value("${rag.review.max-vector-candidates:20}")
    private int maxVectorCandidates;

    @Value("${rag.review.backfill-page-size:200}")
    private int backfillPageSize;

    public ShopReviewVectorIndexService(@Qualifier("shopReviewEmbeddingStore")
                                        ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider,
                                        ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.embeddingStoreProvider = embeddingStoreProvider;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    public ShopRagRebuildResult indexBlog(Long blogId) {
        long start = System.currentTimeMillis();
        if (blogId == null || blogId <= 0) {
            return result(null, 0, 1, 0, start, "blogId invalid");
        }
        Blog blog = blogMapper.selectById(blogId);
        return indexBlog(blog, start);
    }

    public ShopRagRebuildResult indexBlog(Blog blog) {
        return indexBlog(blog, System.currentTimeMillis());
    }

    public ShopRagRebuildResult rebuildShop(Long shopId, Integer limit) {
        long start = System.currentTimeMillis();
        if (!available()) {
            return result(shopId, 0, 0, 0, start, "RAG review index disabled or unavailable");
        }
        if (shopId == null || shopId <= 0) {
            return result(shopId, 0, 1, 0, start, "shopId invalid");
        }
        int safeLimit = normalizeLimit(limit, backfillPageSize);
        List<Blog> blogs = blogMapper.selectActiveBlogsByShopIdForRag(shopId, safeLimit);
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        if (blogs != null) {
            for (Blog blog : blogs) {
                ShopRagRebuildResult item = indexBlog(blog);
                indexed += safe(item.getIndexed());
                skipped += safe(item.getSkipped());
                failed += safe(item.getFailed());
            }
        }
        return result(shopId, indexed, skipped, failed, start,
                "RAG review rebuild finished");
    }

    public ShopRagRebuildResult rebuildAll(Integer shopLimit, Integer perShopLimit) {
        long start = System.currentTimeMillis();
        if (!available()) {
            return result(null, 0, 0, 0, start, "RAG review index disabled or unavailable");
        }
        int safeShopLimit = normalizeLimit(shopLimit, backfillPageSize);
        List<Long> shopIds = blogMapper.selectActiveShopIdsForRag(safeShopLimit);
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        if (shopIds != null) {
            for (Long shopId : shopIds) {
                ShopRagRebuildResult item = rebuildShop(shopId, perShopLimit);
                indexed += safe(item.getIndexed());
                skipped += safe(item.getSkipped());
                failed += safe(item.getFailed());
            }
        }
        ShopRagRebuildResult result = result(null, indexed, skipped, failed, start,
                "RAG review full rebuild finished");
        recordRagIndex("rebuild_all", indexed, skipped, failed, result.getDurationMs());
        return result;
    }

    public List<EvidenceItem> search(Long shopId, String query, String aspect, Integer limit) {
        long start = System.currentTimeMillis();
        if (!available() || shopId == null || shopId <= 0) {
            return new ArrayList<>();
        }
        try {
            EmbeddingModel model = embeddingModelProvider.getIfAvailable();
            EmbeddingStore<TextSegment> store = embeddingStoreProvider.getIfAvailable();
            if (model == null || store == null) {
                return new ArrayList<>();
            }
            String searchText = searchText(shopId, query, aspect);
            Embedding embedding = model.embed(searchText).content();
            int max = Math.max(normalizeLimit(limit, 8), maxVectorCandidates);
            List<EmbeddingMatch<TextSegment>> matches = store.findRelevant(embedding, max, minScore);
            Map<String, EvidenceItem> deduped = new LinkedHashMap<>();
            if (matches != null) {
                for (EmbeddingMatch<TextSegment> match : matches) {
                    EvidenceItem item = toEvidence(shopId, match);
                    if (item != null) {
                        deduped.put(item.getId(), item);
                    }
                }
            }
            List<EvidenceItem> result = new ArrayList<>(deduped.values());
            recordRagSearch(System.currentTimeMillis() - start, result.size(), true);
            return result;
        } catch (RuntimeException e) {
            recordRagSearch(System.currentTimeMillis() - start, 0, false);
            log.warn("RAG review vector search failed, shopId={}, query={}", shopId, AiLogSanitizer.safe(query), e);
            return new ArrayList<>();
        }
    }

    private ShopRagRebuildResult indexBlog(Blog blog, long start) {
        if (!available()) {
            return result(blog == null ? null : blog.getShopId(), 0, 0, 0, start,
                    "RAG review index disabled or unavailable");
        }
        if (!active(blog)) {
            return result(blog == null ? null : blog.getShopId(), 0, 1, 0, start,
                    "blog inactive or invalid");
        }
        try {
            TextSegment segment = documentFactory.toSegment(blog);
            if (segment == null) {
                return result(blog.getShopId(), 0, 1, 0, start, "empty segment");
            }
            EmbeddingModel model = embeddingModelProvider.getIfAvailable();
            EmbeddingStore<TextSegment> store = embeddingStoreProvider.getIfAvailable();
            if (model == null || store == null) {
                return result(blog.getShopId(), 0, 0, 0, start, "embedding store unavailable");
            }
            store.add(model.embed(segment.text()).content(), segment);
            ShopRagRebuildResult result = result(blog.getShopId(), 1, 0, 0, start, "indexed");
            recordRagIndex("index_blog", 1, 0, 0, result.getDurationMs());
            return result;
        } catch (RuntimeException e) {
            log.warn("Index blog review vector failed, blogId={}", blog.getId(), e);
            ShopRagRebuildResult result = result(blog.getShopId(), 0, 0, 1, start, e.getMessage());
            recordRagIndex("index_blog", 0, 0, 1, result.getDurationMs());
            return result;
        }
    }

    private EvidenceItem toEvidence(Long expectedShopId, EmbeddingMatch<TextSegment> match) {
        if (match == null || match.embedded() == null || match.embedded().metadata() == null) {
            return null;
        }
        TextSegment segment = match.embedded();
        Long blogId = readMetadataLong(segment, ReviewVectorDocumentFactory.META_BLOG_ID);
        Long shopId = readMetadataLong(segment, ReviewVectorDocumentFactory.META_SHOP_ID);
        if (blogId == null || shopId == null || !shopId.equals(expectedShopId)) {
            return null;
        }
        Blog blog = blogMapper.selectById(blogId);
        if (!active(blog) || !shopId.equals(blog.getShopId())) {
            return null;
        }
        String indexedHash = segment.metadata().getString(ReviewVectorDocumentFactory.META_CONTENT_HASH);
        if (!documentFactory.contentHash(blog).equals(indexedHash)) {
            return null;
        }
        int liked = blog.getLiked() == null ? 0 : blog.getLiked();
        return EvidenceItem.builder()
                .id(EvidenceItem.reviewId(blog.getId()))
                .type(EvidenceType.REVIEW)
                .sourceId(blog.getId())
                .shopId(blog.getShopId())
                .title("用户评价#" + blog.getId())
                .snippet(AiLogSanitizer.safe(blog.getContent(), SNIPPET_LIMIT))
                .liked(liked)
                .createdAt(blog.getCreateTime())
                .matchedReason("向量召回")
                .score(Math.min(1.0, Math.max(0.0, match.score())))
                .build();
    }

    private boolean available() {
        return ragEnabled && reviewRagEnabled
                && embeddingStoreProvider.getIfAvailable() != null
                && embeddingModelProvider.getIfAvailable() != null;
    }

    private boolean active(Blog blog) {
        return blog != null
                && blog.getId() != null
                && blog.getShopId() != null
                && blog.getContent() != null
                && blog.getStatus() != null
                && blog.getStatus() == BLOG_STATUS_PUBLISHED
                && blog.getDeleted() != null
                && blog.getDeleted() == BLOG_NOT_DELETED;
    }

    private ShopRagRebuildResult result(Long shopId,
                                        int indexed,
                                        int skipped,
                                        int failed,
                                        long start,
                                        String message) {
        return ShopRagRebuildResult.builder()
                .shopId(shopId)
                .indexed(indexed)
                .skipped(skipped)
                .failed(failed)
                .durationMs(System.currentTimeMillis() - start)
                .message(message)
                .build();
    }

    private String searchText(Long shopId, String query, String aspect) {
        return "shopId=" + shopId + "\nquery=" + safe(query) + "\naspect=" + safe(aspect);
    }

    private int normalizeLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.max(1, Math.min(500, limit));
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private Long readLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long readMetadataLong(TextSegment segment, String key) {
        try {
            Long value = segment.metadata().getLong(key);
            if (value != null) {
                return value;
            }
        } catch (RuntimeException ignored) {
            // LangChain4j metadata getters are type-strict; fall through to string parsing.
        }
        try {
            return readLong(segment.metadata().getString(key));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void recordRagSearch(long durationMillis, int count, boolean success) {
        if (aiMetricsService != null) {
            aiMetricsService.recordRagSearch("review_evidence", durationMillis, count, success);
        }
    }

    private void recordRagIndex(String operation, int indexed, int skipped, int failed, long durationMs) {
        if (aiMetricsService != null) {
            aiMetricsService.recordRagIndex(operation, indexed, skipped, failed, durationMs);
        }
    }
}
