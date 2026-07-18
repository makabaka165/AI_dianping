package com.hmdp.ai.domain.artifact;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class Citation {
    private final String citationId;
    private final String knowledgeBaseId;
    private final String documentId;
    private final int documentVersion;
    private final String chunkId;
    private final String title;
    private final String source;
    private final Integer page;
    private final String section;
    private final String sheet;
    private final String rowRange;
    private final double score;
    private final String quotePreview;

    @JsonCreator
    public Citation(@JsonProperty("citationId") String citationId,
                    @JsonProperty("knowledgeBaseId") String knowledgeBaseId,
                    @JsonProperty("documentId") String documentId,
                    @JsonProperty("documentVersion") int documentVersion,
                    @JsonProperty("chunkId") String chunkId,
                    @JsonProperty("title") String title,
                    @JsonProperty("source") String source,
                    @JsonProperty("page") Integer page,
                    @JsonProperty("section") String section,
                    @JsonProperty("sheet") String sheet,
                    @JsonProperty("rowRange") String rowRange,
                    @JsonProperty("score") double score,
                    @JsonProperty("quotePreview") String quotePreview) {
        this.citationId = citationId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentId = documentId;
        this.documentVersion = documentVersion;
        this.chunkId = chunkId;
        this.title = title;
        this.source = source;
        this.page = page;
        this.section = section;
        this.sheet = sheet;
        this.rowRange = rowRange;
        this.score = score;
        this.quotePreview = quotePreview;
    }

    public String getCitationId() { return citationId; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public String getDocumentId() { return documentId; }
    public int getDocumentVersion() { return documentVersion; }
    public String getChunkId() { return chunkId; }
    public String getTitle() { return title; }
    public String getSource() { return source; }
    public Integer getPage() { return page; }
    public String getSection() { return section; }
    public String getSheet() { return sheet; }
    public String getRowRange() { return rowRange; }
    public double getScore() { return score; }
    public String getQuotePreview() { return quotePreview; }
}
