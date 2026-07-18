package com.hmdp.ai.infrastructure.external;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

@Component public class ReferenceFetcher {private final SafeHttpClient http;private final WebContentExtractor extractor;public ReferenceFetcher(SafeHttpClient http,WebContentExtractor extractor){this.http=http;this.extractor=extractor;}
    public String fetch(String url,int maxBytes,int maxChars){OutboundHttpResponse response=http.execute(new OutboundHttpRequest(URI.create(url),"GET",new LinkedHashMap<>(),null,Duration.ofSeconds(10),maxBytes,new LinkedHashSet<>(java.util.Arrays.asList("text/html","text/plain")),false));if(response.getStatusCode()<200||response.getStatusCode()>=300)throw new IllegalArgumentException("REFERENCE_FETCH_STATUS_"+response.getStatusCode());return response.getContentType().equals("text/html")?extractor.extract(response.bodyAsUtf8(),maxChars):truncate(response.bodyAsUtf8(),maxChars);}
    private String truncate(String value,int max){return value.length()<=max?value:value.substring(0,max);}}
