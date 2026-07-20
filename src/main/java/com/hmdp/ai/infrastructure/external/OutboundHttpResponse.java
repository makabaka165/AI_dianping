package com.hmdp.ai.infrastructure.external;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class OutboundHttpResponse {
    private final int statusCode;
    private final String contentType;
    private final byte[] body;
    private final Map<String, List<String>> headers;

    public OutboundHttpResponse(int statusCode, String contentType, byte[] body) {
        this(statusCode, contentType, body, Collections.emptyMap());
    }

    public OutboundHttpResponse(int statusCode, String contentType, byte[] body,
                                Map<String, List<String>> headers) {
        this.statusCode = statusCode;
        this.contentType = contentType;
        this.body = body.clone();
        this.headers = Collections.unmodifiableMap(headers);
    }

    public int getStatusCode() { return statusCode; }
    public String getContentType() { return contentType; }
    public byte[] getBody() { return body.clone(); }
    public Map<String, List<String>> getHeaders() { return headers; }

    public String firstHeader(String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
    }

    public String bodyAsUtf8() { return new String(body, StandardCharsets.UTF_8); }
}
