package com.hmdp.ai.domain.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded, persistence-ready measurements from one hybrid retrieval operation.
 */
public final class RetrievalTrace {
    private final int knowledgeBaseVersion;
    private final String indexVersion;
    private final int vectorCandidateCount;
    private final int lexicalCandidateCount;
    private final int fusedCandidateCount;
    private final int rerankedCandidateCount;
    private final List<String> selectedChunkIds;

    public RetrievalTrace(int knowledgeBaseVersion, String indexVersion, int vectorCandidateCount,
                          int lexicalCandidateCount, int fusedCandidateCount, int rerankedCandidateCount,
                          List<String> selectedChunkIds) {
        this.knowledgeBaseVersion = knowledgeBaseVersion;
        this.indexVersion = indexVersion;
        this.vectorCandidateCount = vectorCandidateCount;
        this.lexicalCandidateCount = lexicalCandidateCount;
        this.fusedCandidateCount = fusedCandidateCount;
        this.rerankedCandidateCount = rerankedCandidateCount;
        this.selectedChunkIds = Collections.unmodifiableList(new ArrayList<>(selectedChunkIds));
    }

    public static RetrievalTrace unavailable(List<RetrievedChunk> chunks) {
        List<String> selected = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            selected.add(chunk.getChunk().getId());
        }
        return new RetrievalTrace(0, "unknown", 0, 0, selected.size(), selected.size(), selected);
    }

    public int getKnowledgeBaseVersion() { return knowledgeBaseVersion; }
    public String getIndexVersion() { return indexVersion; }
    public int getVectorCandidateCount() { return vectorCandidateCount; }
    public int getLexicalCandidateCount() { return lexicalCandidateCount; }
    public int getFusedCandidateCount() { return fusedCandidateCount; }
    public int getRerankedCandidateCount() { return rerankedCandidateCount; }
    public List<String> getSelectedChunkIds() { return selectedChunkIds; }
}
