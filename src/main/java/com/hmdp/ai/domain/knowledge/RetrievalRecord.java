package com.hmdp.ai.domain.knowledge;

import com.hmdp.ai.domain.observability.InvocationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RetrievalRecord {
    private final InvocationContext context;
    private final String query;
    private final String knowledgeBaseId;
    private final int knowledgeBaseVersion;
    private final String indexVersion;
    private final int vectorCandidateCount;
    private final int lexicalCandidateCount;
    private final int fusedCandidateCount;
    private final int rerankedCandidateCount;
    private final List<String> selectedChunkIds;
    private final List<String> citationIds;
    private final String rerankMode;
    private final long latencyMs;
    private final String status;
    private final String errorCode;

    private RetrievalRecord(InvocationContext context, String query, String knowledgeBaseId,
                            int knowledgeBaseVersion, String indexVersion, int vectorCandidateCount,
                            int lexicalCandidateCount, int fusedCandidateCount, int rerankedCandidateCount,
                            List<String> selectedChunkIds, List<String> citationIds, String rerankMode,
                            long latencyMs, String status, String errorCode) {
        this.context = Objects.requireNonNull(context, "context");
        this.query = query;
        this.knowledgeBaseId = knowledgeBaseId;
        this.knowledgeBaseVersion = knowledgeBaseVersion;
        this.indexVersion = indexVersion;
        this.vectorCandidateCount = vectorCandidateCount;
        this.lexicalCandidateCount = lexicalCandidateCount;
        this.fusedCandidateCount = fusedCandidateCount;
        this.rerankedCandidateCount = rerankedCandidateCount;
        this.selectedChunkIds = immutable(selectedChunkIds);
        this.citationIds = immutable(citationIds);
        this.rerankMode = rerankMode;
        this.latencyMs = latencyMs;
        this.status = status;
        this.errorCode = errorCode;
    }

    public static RetrievalRecord succeeded(InvocationContext context, KnowledgeRetrievalRequest request,
                                            HybridRetrievalResult result, long latencyMs) {
        List<String> citations = new ArrayList<>();
        for (RetrievedChunk chunk : result.getChunks()) {
            if (chunk.getCitation() != null && chunk.getCitation().getCitationId() != null) {
                citations.add(chunk.getCitation().getCitationId());
            }
        }
        RetrievalTrace trace = result.getTrace();
        return new RetrievalRecord(context, request.getQuery(), request.getKnowledgeBaseId(),
                trace.getKnowledgeBaseVersion(), trace.getIndexVersion(), trace.getVectorCandidateCount(),
                trace.getLexicalCandidateCount(), trace.getFusedCandidateCount(),
                trace.getRerankedCandidateCount(), trace.getSelectedChunkIds(), citations,
                result.getRerankMode(), latencyMs, "SUCCEEDED", null);
    }

    public static RetrievalRecord failed(InvocationContext context, String query, String knowledgeBaseId,
                                         Integer requestedVersion, String errorCode, long latencyMs) {
        return new RetrievalRecord(context, query, knowledgeBaseId, requestedVersion == null ? 0 : requestedVersion,
                "unresolved", 0, 0, 0, 0, Collections.emptyList(), Collections.emptyList(), "NONE",
                latencyMs, "FAILED", errorCode);
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null
                ? Collections.emptyList() : values));
    }

    public InvocationContext getContext() { return context; }
    public String getQuery() { return query; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public int getKnowledgeBaseVersion() { return knowledgeBaseVersion; }
    public String getIndexVersion() { return indexVersion; }
    public int getVectorCandidateCount() { return vectorCandidateCount; }
    public int getLexicalCandidateCount() { return lexicalCandidateCount; }
    public int getFusedCandidateCount() { return fusedCandidateCount; }
    public int getRerankedCandidateCount() { return rerankedCandidateCount; }
    public List<String> getSelectedChunkIds() { return selectedChunkIds; }
    public List<String> getCitationIds() { return citationIds; }
    public String getRerankMode() { return rerankMode; }
    public long getLatencyMs() { return latencyMs; }
    public String getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
}
