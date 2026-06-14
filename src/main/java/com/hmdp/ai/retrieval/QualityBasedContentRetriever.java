package com.hmdp.ai.retrieval;

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

import static dev.langchain4j.internal.Utils.randomUUID;
import static java.util.Collections.emptyList;

@Slf4j
public class QualityBasedContentRetriever implements ContentRetriever {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final double minScore;
    private final int maxResults;

    @Builder
    public QualityBasedContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                        EmbeddingModel embeddingModel,
                                        double minScore,
                                        int maxResults) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.minScore = minScore;
        this.maxResults = maxResults;
    }

    @Override
    public List<Content> retrieve(Query query) {
        // 1. 将查询转换为嵌入向量
        Embedding queryEmbedding = embeddingModel.embed(query.text()).content();

        // 2. 在向量数据库中搜索相关的文本片段
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult;
        try {
            searchResult = embeddingStore.search(searchRequest);
        } catch (Exception e) {
            log.error("向量数据库搜索失败: {}", e.getMessage(), e);
            return emptyList();
        }

        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        log.info("查询 '{}' 找到 {} 个匹配结果", query.text(), matches.size());

        if (matches.isEmpty()) {
            return emptyList();
        }

        // 3. 将匹配结果转换为内容列表
        return matches.stream()
                .map(match -> {
                    TextSegment segment = match.embedded();
                    log.debug("匹配片段 (得分: {}): {}", match.score(), segment.text());
                    return Content.from(segment);
                })
                .collect(Collectors.toList());
    }
}
