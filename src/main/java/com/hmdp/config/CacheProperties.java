package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.cache")
public class CacheProperties {

    private long ttlJitterMinutes = 5L;
    private Mutex mutex = new Mutex();
    private Logical logical = new Logical();
    private RebuildExecutor rebuildExecutor = new RebuildExecutor();

    @Data
    public static class Mutex {
        private long waitTimeoutMillis = 300L;
        private long leaseTimeSeconds = 10L;
        private RetryAfterFail retryAfterFail = new RetryAfterFail();
    }

    @Data
    public static class RetryAfterFail {
        private boolean enabled = true;
        private int maxAttempts = 3;
        private long sleepMillis = 50L;
        private boolean fallbackToDb = false;
    }

    @Data
    public static class Logical {
        private long minPhysicalTtlMinutes = 10L;
    }

    @Data
    public static class RebuildExecutor {
        private int coreSize = 4;
        private int maxSize = 10;
        private int queueCapacity = 100;
        private int keepAliveSeconds = 60;
        private int awaitTerminationSeconds = 10;
    }
}
