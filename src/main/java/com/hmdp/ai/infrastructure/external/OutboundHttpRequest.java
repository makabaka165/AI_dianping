package com.hmdp.ai.infrastructure.external;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class OutboundHttpRequest {
    private final URI uri; private final String method; private final Map<String,String> headers;
    private final byte[] body; private final Duration timeout; private final int maxResponseBytes;
    private final Set<String> allowedContentTypes; private final boolean allowPrivateNetwork;
    public OutboundHttpRequest(URI uri,String method,Map<String,String> headers,byte[] body,Duration timeout,
                               int maxResponseBytes,Set<String> allowedContentTypes,boolean allowPrivateNetwork){
        this.uri=uri;this.method=method;this.headers=Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body=body==null?new byte[0]:body.clone();this.timeout=timeout;this.maxResponseBytes=maxResponseBytes;
        this.allowedContentTypes=allowedContentTypes;this.allowPrivateNetwork=allowPrivateNetwork;}
    public URI getUri(){return uri;}public String getMethod(){return method;}public Map<String,String>getHeaders(){return headers;}
    public byte[]getBody(){return body.clone();}public Duration getTimeout(){return timeout;}public int getMaxResponseBytes(){return maxResponseBytes;}
    public Set<String>getAllowedContentTypes(){return allowedContentTypes;}public boolean isAllowPrivateNetwork(){return allowPrivateNetwork;}
}
