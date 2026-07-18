package com.hmdp.ai.domain.knowledge.parsing;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public final class ParsedDocument {
    private final String title;
    private final String mimeType;
    private final List<ParsedSection> sections;
    private final List<ParsedTable> tables;
    private final List<ParseWarning> warnings;
    public ParsedDocument(String title, String mimeType, List<ParsedSection> sections,
                          List<ParsedTable> tables, List<ParseWarning> warnings) {
        this.title = title; this.mimeType = mimeType;
        this.sections = Collections.unmodifiableList(sections);
        this.tables = Collections.unmodifiableList(tables);
        this.warnings = Collections.unmodifiableList(warnings);
    }
    public String getTitle() { return title; }
    public String getMimeType() { return mimeType; }
    public List<ParsedSection> getSections() { return sections; }
    public List<ParsedTable> getTables() { return tables; }
    public List<ParseWarning> getWarnings() { return warnings; }
    public String getPlainText() { return sections.stream().map(ParsedSection::getText).collect(Collectors.joining("\n\n")); }
}
