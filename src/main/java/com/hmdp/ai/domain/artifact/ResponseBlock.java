package com.hmdp.ai.domain.artifact;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ResponseBlock {
    private final ResponseBlockType type;
    private final String text;
    private final Map<String, Object> data;

    public ResponseBlock(ResponseBlockType type, String text, Map<String, Object> data) {
        this.type = type;
        this.text = text;
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data == null
                ? Collections.emptyMap() : data));
    }

    public ResponseBlockType getType() { return type; }
    public String getText() { return text; }
    public Map<String, Object> getData() { return data; }
}
