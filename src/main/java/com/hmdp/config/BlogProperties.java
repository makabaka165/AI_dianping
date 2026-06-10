package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.blog")
public class BlogProperties {
    private int hotCacheSize = 100;
    private int likeTopLimit = 5;
    private int feedPageSize = 10;
    private int feedFetchMultiplier = 2;
    private int feedInboxMaxSize = 1000;
    private int largeAuthorFansThreshold = 10000;
}
