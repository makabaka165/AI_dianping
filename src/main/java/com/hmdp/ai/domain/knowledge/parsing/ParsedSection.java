package com.hmdp.ai.domain.knowledge.parsing;

import java.util.Collections;
import java.util.List;

public final class ParsedSection {
    private final String title;
    private final String text;
    private final Integer page;
    private final List<String> headingPath;
    private final int sourceOffsetStart;
    private final int sourceOffsetEnd;
    public ParsedSection(String title, String text, Integer page, List<String> headingPath, int start, int end) {
        this.title = title; this.text = text; this.page = page;
        this.headingPath = headingPath == null ? Collections.emptyList() : Collections.unmodifiableList(headingPath);
        this.sourceOffsetStart = start; this.sourceOffsetEnd = end;
    }
    public String getTitle() { return title; }
    public String getText() { return text; }
    public Integer getPage() { return page; }
    public List<String> getHeadingPath() { return headingPath; }
    public int getSourceOffsetStart() { return sourceOffsetStart; }
    public int getSourceOffsetEnd() { return sourceOffsetEnd; }
}
