package com.hmdp.ai.domain.external;

import java.time.Instant;

public final class SearchResult {private final String title,url,snippet,sourceDomain,content;private final Instant publishedAt,retrievedAt;
    public SearchResult(String title,String url,String snippet,Instant publishedAt,Instant retrievedAt,String sourceDomain,String content){this.title=title;this.url=url;this.snippet=snippet;this.publishedAt=publishedAt;this.retrievedAt=retrievedAt;this.sourceDomain=sourceDomain;this.content=content;}
    public String getTitle(){return title;}public String getUrl(){return url;}public String getSnippet(){return snippet;}public Instant getPublishedAt(){return publishedAt;}public Instant getRetrievedAt(){return retrievedAt;}public String getSourceDomain(){return sourceDomain;}public String getContent(){return content;}}
