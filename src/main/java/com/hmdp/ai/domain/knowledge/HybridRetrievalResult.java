package com.hmdp.ai.domain.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HybridRetrievalResult {
    private final List<RetrievedChunk> chunks;
    private final String rerankMode;
    private final List<String> warnings;
    private final RetrievalTrace trace;

    public HybridRetrievalResult(List<RetrievedChunk> chunks, String rerankMode, List<String> warnings) {
        this(chunks, rerankMode, warnings, RetrievalTrace.unavailable(chunks));
    }

    public HybridRetrievalResult(List<RetrievedChunk> chunks, String rerankMode, List<String> warnings,
                                 RetrievalTrace trace) {
        this.chunks = Collections.unmodifiableList(new ArrayList<>(chunks));
        this.rerankMode = rerankMode;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        this.trace = trace;
    }

    public List<RetrievedChunk> getChunks() { return chunks; }
    public String getRerankMode() { return rerankMode; }
    public List<String> getWarnings() { return warnings; }
    public RetrievalTrace getTrace() { return trace; }
}
