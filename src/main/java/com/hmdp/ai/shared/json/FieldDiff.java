package com.hmdp.ai.shared.json;

import com.fasterxml.jackson.databind.JsonNode;

public final class FieldDiff {
    private final String field;
    private final JsonNode left;
    private final JsonNode right;

    public FieldDiff(String field, JsonNode left, JsonNode right) {
        this.field = field;
        this.left = left;
        this.right = right;
    }

    public String getField() { return field; }
    public JsonNode getLeft() { return left; }
    public JsonNode getRight() { return right; }
}
