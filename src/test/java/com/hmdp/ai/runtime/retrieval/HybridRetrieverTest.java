package com.hmdp.ai.runtime.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.knowledge.EmbeddingGateway;
import com.hmdp.ai.domain.knowledge.HybridRetrievalResult;
import com.hmdp.ai.domain.knowledge.IndexHit;
import com.hmdp.ai.domain.knowledge.KnowledgeBaseVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.KnowledgeSearchScope;
import com.hmdp.ai.domain.knowledge.RerankModelGateway;
import com.hmdp.ai.shared.text.TextNormalizer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrieverTest {
    @Test
    void executesAclScopedRecallAndReturnsCompleteRetrievalTrace() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeIndexPort index = mock(KnowledgeIndexPort.class);
        EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
        RerankModelGateway rerank = mock(RerankModelGateway.class);
        KnowledgeBaseVersion knowledgeBase = new KnowledgeBaseVersion("kbv", "tenant", "workspace",
                "kb", 1, "embedding", 3, "{}",
                "{\"vectorTopN\":10,\"lexicalTopN\":10,\"finalTopK\":3}",
                "idx", "READY", "PUBLISHED");
        when(repository.findPublishedVersion("tenant", "workspace", "kb", 1))
                .thenReturn(Optional.of(knowledgeBase));
        when(embeddings.embed(eq("tenant"), eq("workspace"), eq("embedding"), anyList(), eq(3)))
                .thenAnswer(invocation -> {
                    List<?> queries = invocation.getArgument(3);
                    List<float[]> values = new ArrayList<>();
                    for (int i = 0; i < queries.size(); i++) {
                        values.add(new float[]{1, 0, 0});
                    }
                    return values;
                });
        when(index.vectorSearch(any(), any(), eq(10))).thenReturn(Arrays.asList(
                new IndexHit("a", .9), new IndexHit("b", .8)));
        when(index.lexicalSearch(any(), anyString(), eq(10))).thenReturn(Arrays.asList(
                new IndexHit("b", 4), new IndexHit("c", 3)));
        when(repository.findChunksByIds(eq("tenant"), eq("workspace"), anyList()))
                .thenReturn(Arrays.asList(chunk("a", "d1"), chunk("b", "d2"), chunk("c", "d3")));
        ObjectMapper mapper = new ObjectMapper();
        HybridRetriever retriever = new HybridRetriever(repository, index, embeddings,
                new TextNormalizer(), new RrfFusionRanker(), new FallbackRrfReranker(rerank),
                new ContextCompressor(), new CitationBuilder(mapper), mapper);

        HybridRetrievalResult result = retriever.retrieve("tenant", "workspace", "user", "kb", 1,
                "service quality", 3);

        assertEquals(3, result.getChunks().size());
        assertEquals("b", result.getChunks().get(0).getChunk().getId());
        assertEquals("FALLBACK_RRF", result.getRerankMode());
        assertEquals(1, result.getTrace().getKnowledgeBaseVersion());
        assertEquals("idx", result.getTrace().getIndexVersion());
        assertEquals(2, result.getTrace().getVectorCandidateCount());
        assertEquals(2, result.getTrace().getLexicalCandidateCount());
        assertEquals(3, result.getTrace().getFusedCandidateCount());
        assertEquals(3, result.getTrace().getRerankedCandidateCount());
        assertEquals(Arrays.asList("b", "a", "c"), result.getTrace().getSelectedChunkIds());

        ArgumentCaptor<KnowledgeSearchScope> scope = ArgumentCaptor.forClass(KnowledgeSearchScope.class);
        verify(index, atLeastOnce()).vectorSearch(scope.capture(), any(), eq(10));
        assertEquals("tenant", scope.getValue().getTenantId());
        assertEquals("workspace", scope.getValue().getWorkspaceId());
        assertEquals("kb", scope.getValue().getKnowledgeBaseId());
        assertEquals("user", scope.getValue().getUserId());
    }

    private KnowledgeChunk chunk(String id, String document) {
        return new KnowledgeChunk(id, "tenant", "workspace", "kb", 1, document, 1,
                "dv-" + id, "idx", 1, "text " + id, "text " + id, "hash-" + id, 3,
                new float[]{1, 0, 0}, .8, "text/plain", null, "section", null, null,
                null, null, null, null, 0, 6, "{\"title\":\"Doc\"}");
    }
}
