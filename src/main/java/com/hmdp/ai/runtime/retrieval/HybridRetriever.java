package com.hmdp.ai.runtime.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.artifact.Citation;
import com.hmdp.ai.domain.knowledge.EmbeddingGateway;
import com.hmdp.ai.domain.knowledge.HybridRetrievalResult;
import com.hmdp.ai.domain.knowledge.IndexHit;
import com.hmdp.ai.domain.knowledge.KnowledgeBaseVersion;
import com.hmdp.ai.domain.knowledge.KnowledgeChunk;
import com.hmdp.ai.domain.knowledge.KnowledgeIndexPort;
import com.hmdp.ai.domain.knowledge.KnowledgeRepository;
import com.hmdp.ai.domain.knowledge.KnowledgeRetrievalRequest;
import com.hmdp.ai.domain.knowledge.KnowledgeRetriever;
import com.hmdp.ai.domain.knowledge.KnowledgeSearchScope;
import com.hmdp.ai.domain.knowledge.RetrievalTrace;
import com.hmdp.ai.domain.knowledge.RetrievedChunk;
import com.hmdp.ai.shared.text.TextNormalizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class HybridRetriever implements KnowledgeRetriever {
    private final KnowledgeRepository repository;
    private final KnowledgeIndexPort index;
    private final EmbeddingGateway embeddings;
    private final TextNormalizer normalizer;
    private final RrfFusionRanker fusion;
    private final RerankService reranker;
    private final ContextCompressor compressor;
    private final CitationBuilder citations;
    private final ObjectMapper mapper;

    public HybridRetriever(KnowledgeRepository repository, KnowledgeIndexPort index,
                           EmbeddingGateway embeddings, TextNormalizer normalizer,
                           RrfFusionRanker fusion, RerankService reranker,
                           ContextCompressor compressor, CitationBuilder citations,
                           ObjectMapper mapper) {
        this.repository = repository;
        this.index = index;
        this.embeddings = embeddings;
        this.normalizer = normalizer;
        this.fusion = fusion;
        this.reranker = reranker;
        this.compressor = compressor;
        this.citations = citations;
        this.mapper = mapper;
    }

    @Override
    public HybridRetrievalResult retrieve(String tenantId, String workspaceId, String userId,
                                          String knowledgeBaseId, Integer version, String query,
                                          Integer requestedTopK) {
        return retrieve(KnowledgeRetrievalRequest.interactive(tenantId, workspaceId, userId,
                knowledgeBaseId, version, query, requestedTopK));
    }

    @Override
    public HybridRetrievalResult retrieve(KnowledgeRetrievalRequest request) {
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new IllegalArgumentException("QUERY_REQUIRED");
        }

        KnowledgeBaseVersion knowledgeBase = repository.findPublishedVersion(
                        request.getTenantId(), request.getWorkspaceId(), request.getKnowledgeBaseId(),
                        request.getKnowledgeBaseVersion())
                .orElseThrow(() -> new IllegalArgumentException("KNOWLEDGE_VERSION_NOT_PUBLISHED"));
        JsonNode policy = readPolicy(knowledgeBase.getRetrievalPolicyJson());
        int vectorTop = Math.max(1, policy.path("vectorTopN").asInt(30));
        int lexicalTop = Math.max(1, policy.path("lexicalTopN").asInt(30));
        int defaultTop = policy.path("finalTopK").asInt(8);
        int finalTop = Math.max(1, Math.min(request.getTopK() == null ? defaultTop : request.getTopK(), 50));

        List<String> queries = expand(request.getQuery());
        List<float[]> queryVectors = embeddings.embed(request.getTenantId(), request.getWorkspaceId(),
                knowledgeBase.getEmbeddingModelProfileId(), queries, knowledgeBase.getEmbeddingDimension());
        KnowledgeSearchScope scope = new KnowledgeSearchScope(request.getTenantId(), request.getWorkspaceId(),
                request.getKnowledgeBaseId(), knowledgeBase.getIndexVersion(), request.getUserId());

        List<List<IndexHit>> rankings = new ArrayList<>();
        int vectorCandidateCount = 0;
        int lexicalCandidateCount = 0;
        for (int i = 0; i < queries.size(); i++) {
            List<IndexHit> vectorHits = index.vectorSearch(scope, queryVectors.get(i), vectorTop);
            List<IndexHit> lexicalHits = index.lexicalSearch(scope,
                    normalizer.searchText(queries.get(i)), lexicalTop);
            vectorCandidateCount += vectorHits.size();
            lexicalCandidateCount += lexicalHits.size();
            rankings.add(vectorHits);
            rankings.add(lexicalHits);
        }

        List<RrfFusionRanker.FusedHit> fused = fusion.fuse(rankings, 60);
        List<String> ids = fused.stream().map(RrfFusionRanker.FusedHit::getChunkId)
                .collect(Collectors.toList());
        Map<String, KnowledgeChunk> chunks = repository.findChunksByIds(
                        request.getTenantId(), request.getWorkspaceId(), ids).stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, value -> value));

        List<KnowledgeChunk> ordered = new ArrayList<>();
        List<RrfFusionRanker.FusedHit> available = new ArrayList<>();
        for (RrfFusionRanker.FusedHit hit : fused) {
            KnowledgeChunk chunk = chunks.get(hit.getChunkId());
            if (chunk != null) {
                ordered.add(chunk);
                available.add(hit);
            }
        }

        List<Double> fallbackScores = available.stream()
                .map(RrfFusionRanker.FusedHit::getScore).collect(Collectors.toList());
        String rerankProfile = policy.path("rerankModelProfileId").asText(null);
        RerankOutcome outcome = reranker.rerank(request.getTenantId(), request.getWorkspaceId(),
                rerankProfile, request.getQuery(), ordered, fallbackScores);

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            double rerankScore = i < outcome.getScores().size()
                    ? outcome.getScores().get(i) : fallbackScores.get(i);
            candidates.add(new Candidate(ordered.get(i), available.get(i), rerankScore));
        }
        candidates.sort(Comparator.comparingDouble((Candidate value) -> value.rerank)
                .reversed().thenComparing(value -> value.chunk.getId()));

        Set<String> hashes = new HashSet<>();
        Map<String, Integer> perDocument = new HashMap<>();
        List<RetrievedChunk> results = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!hashes.add(candidate.chunk.getContentHash())) {
                continue;
            }
            if (perDocument.merge(candidate.chunk.getDocumentId(), 1, Integer::sum) > 3) {
                continue;
            }
            String text = compressor.compress(candidate.chunk.getContent(), request.getQuery(),
                    Math.max(300, policy.path("maxContextCharsPerChunk").asInt(1200)));
            Citation citation = citations.build(candidate.chunk, candidate.rerank);
            Map<String, Object> metadata = metadata(candidate.chunk.getMetadataJson());
            double score = "MODEL".equals(outcome.getMode())
                    ? candidate.rerank : candidate.hit.getScore();
            results.add(new RetrievedChunk(candidate.chunk, score, candidate.hit.getVectorScore(),
                    candidate.hit.getLexicalScore(), candidate.rerank, text, metadata, citation));
            if (results.size() >= finalTop) {
                break;
            }
        }

        List<String> selectedIds = results.stream().map(value -> value.getChunk().getId())
                .collect(Collectors.toList());
        RetrievalTrace trace = new RetrievalTrace(knowledgeBase.getVersion(), knowledgeBase.getIndexVersion(),
                vectorCandidateCount, lexicalCandidateCount, fused.size(), candidates.size(), selectedIds);
        List<String> warnings = results.isEmpty()
                ? Collections.singletonList("INSUFFICIENT_KNOWLEDGE_EVIDENCE") : Collections.emptyList();
        return new HybridRetrievalResult(results, outcome.getMode(), warnings, trace);
    }

    private List<String> expand(String query) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(query.trim());
        for (String part : query.split("[,;!?\\u3002\\uFF0C\\uFF01\\uFF1F]")) {
            String value = part.trim();
            if (value.length() >= 4) {
                values.add(value);
            }
            if (values.size() >= 3) {
                break;
            }
        }
        return new ArrayList<>(values);
    }

    private JsonNode readPolicy(String json) {
        try {
            return mapper.readTree(json == null || json.trim().isEmpty() ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalStateException("RETRIEVAL_POLICY_INVALID", e);
        }
    }

    private Map<String, Object> metadata(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static final class Candidate {
        private final KnowledgeChunk chunk;
        private final RrfFusionRanker.FusedHit hit;
        private final double rerank;

        private Candidate(KnowledgeChunk chunk, RrfFusionRanker.FusedHit hit, double rerank) {
            this.chunk = chunk;
            this.hit = hit;
            this.rerank = rerank;
        }
    }
}
