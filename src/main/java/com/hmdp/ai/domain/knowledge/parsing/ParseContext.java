package com.hmdp.ai.domain.knowledge.parsing;

public final class ParseContext {
    private final String fileName;
    private final String mimeType;
    private final long size;
    private final int maxPages;
    private final int maxCells;
    public ParseContext(String fileName, String mimeType, long size, int maxPages, int maxCells) {
        this.fileName = fileName; this.mimeType = mimeType; this.size = size;
        this.maxPages = maxPages; this.maxCells = maxCells;
    }
    public String getFileName() { return fileName; }
    public String getMimeType() { return mimeType; }
    public long getSize() { return size; }
    public int getMaxPages() { return maxPages; }
    public int getMaxCells() { return maxCells; }
}
