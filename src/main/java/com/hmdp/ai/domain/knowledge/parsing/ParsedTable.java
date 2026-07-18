package com.hmdp.ai.domain.knowledge.parsing;

import java.util.Collections;
import java.util.List;

public final class ParsedTable {
    private final String name;
    private final Integer page;
    private final List<ParsedCell> cells;
    public ParsedTable(String name, Integer page, List<ParsedCell> cells) {
        this.name = name; this.page = page; this.cells = Collections.unmodifiableList(cells);
    }
    public String getName() { return name; }
    public Integer getPage() { return page; }
    public List<ParsedCell> getCells() { return cells; }
}
