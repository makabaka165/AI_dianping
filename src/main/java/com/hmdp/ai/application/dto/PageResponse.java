package com.hmdp.ai.application.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PageResponse<T> {
    private final List<T> items;
    private final long total;
    private final int page;
    private final int size;

    public PageResponse(List<T> items, long total, int page, int size) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public List<T> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
