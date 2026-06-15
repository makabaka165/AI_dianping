package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hmdp.redis.redisson")
public class RedissonProperties {

    private String host;

    private Integer port;

    private String password;

    private Integer database;

    private Boolean ssl;

    private Integer timeoutMillis = 3000;

    private Integer connectTimeoutMillis = 3000;

    private Integer retryAttempts = 3;

    private Integer retryIntervalMillis = 1500;
}
