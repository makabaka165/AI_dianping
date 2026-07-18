package com.hmdp.ai.infrastructure.external;

import java.nio.charset.StandardCharsets;

public final class OutboundHttpResponse {private final int statusCode;private final String contentType;private final byte[] body;
    public OutboundHttpResponse(int statusCode,String contentType,byte[]body){this.statusCode=statusCode;this.contentType=contentType;this.body=body.clone();}
    public int getStatusCode(){return statusCode;}public String getContentType(){return contentType;}public byte[]getBody(){return body.clone();}
    public String bodyAsUtf8(){return new String(body,StandardCharsets.UTF_8);}}
