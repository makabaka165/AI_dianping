package com.hmdp.ai.application.dto.agent;

import com.fasterxml.jackson.databind.JsonNode;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class InputPartRequest {
    @NotNull private InputPartType type;
    @Size(max = 8000) private String text;
    private JsonNode data;
    @Size(max = 2048) private String uri;

    public InputPartType getType() { return type; }
    public void setType(InputPartType type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public JsonNode getData() { return data; }
    public void setData(JsonNode data) { this.data = data; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
}
