package com.hmdp.ai.retrieval;

import com.hmdp.ai.infra.AiLogSanitizer;
import com.hmdp.ai.port.PlatformPolicyDocumentPort;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;

/**
 * Legacy LangChain4j compatibility retriever. New agent workflows use the
 * ACL-scoped {@code com.hmdp.ai.runtime.retrieval.HybridRetriever} module.
 */
@Slf4j
@Deprecated
public class QualityBasedContentRetriever implements ContentRetriever {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final PlatformPolicyDocumentPort platformPolicyDocumentPort;
    private final double minScore;
    private final int maxResults;
    private final int maxVectorCandidates;

    @Builder
    public QualityBasedContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                        EmbeddingModel embeddingModel,
                                        PlatformPolicyDocumentPort platformPolicyDocumentPort,
                                        double minScore,
                                        int maxResults) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.platformPolicyDocumentPort = platformPolicyDocumentPort;
        this.minScore = minScore;
        this.maxResults = maxResults;
        this.maxVectorCandidates = Math.max(maxResults, Math.min(50, Math.max(maxResults * 4, maxResults + 10)));
    }

    @Override
    public List<Content> retrieve(Query query) {
        // 1. 将查询转换为嵌入向量
        String queryText = query == null ? "" : query.text();
        Embedding queryEmbedding = embeddingModel.embed(queryText).content();

        // 2. 在向量数据库中搜索相关的文本片段
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxVectorCandidates)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult;
        try {
            searchResult = embeddingStore.search(searchRequest);
        } catch (Exception e) {
            log.warn("Platform policy vector search failed, errorType={}, reason={}",
                    e.getClass().getSimpleName(), AiLogSanitizer.safe(e.getMessage(), 100));
            return emptyList();
        }

        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        log.info("Platform policy retrieval matched {} raw vector results", matches.size());

        if (matches.isEmpty()) {
            return emptyList();
        }

        // 3. 将匹配结果转换为内容列表
        return matches.stream()
                .filter(this::activeDocumentChunk)
                .limit(maxResults)
                .map(match -> {
                    TextSegment segment = match.embedded();
                    log.debug("Platform policy match accepted, score={}, documentId={}",
                            match.score(), AiLogSanitizer.safeKey(metadata(segment, PlatformPolicyVectorDocumentFactory.META_DOCUMENT_ID)));
                    return Content.from(segment);
                })
                .collect(Collectors.toList());
    }

    private boolean activeDocumentChunk(EmbeddingMatch<TextSegment> match) {
        if (platformPolicyDocumentPort == null) {
            return true;
        }
        TextSegment segment = match == null ? null : match.embedded();
        String documentId = metadata(segment, PlatformPolicyVectorDocumentFactory.META_DOCUMENT_ID);
        String contentHash = metadata(segment, PlatformPolicyVectorDocumentFactory.META_CONTENT_HASH);
        boolean active = platformPolicyDocumentPort.isActiveDocumentChunk(documentId, contentHash);
        if (!active) {
            log.debug("Platform policy match filtered, score={}, documentId={}",
                    match == null ? null : match.score(), AiLogSanitizer.safeKey(documentId));
        }
        return active;
    }

    private String metadata(TextSegment segment, String key) {
        if (segment == null || segment.metadata() == null || key == null) {
            return null;
        }
        try {
            return segment.metadata().getString(key);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
