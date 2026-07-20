package com.hmdp.ai.domain.knowledge;

public final class IndexVerificationResult {
    private final long expectedChunkCount;
    private final long indexedChunkCount;
    private final long expectedDocumentCount;
    private final long indexedDocumentCount;
    private final boolean vectorSearchPassed;
    private final boolean lexicalSearchPassed;
    private final boolean dimensionPassed;
    private final boolean aclPassed;

    public IndexVerificationResult(long expectedChunkCount, long indexedChunkCount,
                                   long expectedDocumentCount, long indexedDocumentCount,
                                   boolean vectorSearchPassed, boolean lexicalSearchPassed,
                                   boolean dimensionPassed, boolean aclPassed) {
        this.expectedChunkCount = expectedChunkCount;
        this.indexedChunkCount = indexedChunkCount;
        this.expectedDocumentCount = expectedDocumentCount;
        this.indexedDocumentCount = indexedDocumentCount;
        this.vectorSearchPassed = vectorSearchPassed;
        this.lexicalSearchPassed = lexicalSearchPassed;
        this.dimensionPassed = dimensionPassed;
        this.aclPassed = aclPassed;
    }

    public boolean isValid() {
        return expectedChunkCount == indexedChunkCount
                && expectedDocumentCount == indexedDocumentCount
                && vectorSearchPassed && lexicalSearchPassed && dimensionPassed && aclPassed;
    }

    public long getExpectedChunkCount() { return expectedChunkCount; }
    public long getIndexedChunkCount() { return indexedChunkCount; }
    public long getExpectedDocumentCount() { return expectedDocumentCount; }
    public long getIndexedDocumentCount() { return indexedDocumentCount; }
    public boolean isVectorSearchPassed() { return vectorSearchPassed; }
    public boolean isLexicalSearchPassed() { return lexicalSearchPassed; }
    public boolean isDimensionPassed() { return dimensionPassed; }
    public boolean isAclPassed() { return aclPassed; }
}
