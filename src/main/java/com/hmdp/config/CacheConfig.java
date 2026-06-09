package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Configuration
public class CacheConfig {

    @Bean("cacheRebuildExecutor")
    public ThreadPoolTaskExecutor cacheRebuildExecutor(CacheProperties cacheProperties) {
        CacheProperties.RebuildExecutor properties = cacheProperties.getRebuildExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCoreSize());
        executor.setMaxPoolSize(properties.getMaxSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setThreadNamePrefix("cache-rebuild-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        executor.setRejectedExecutionHandler((runnable, poolExecutor) -> {
            log.warn("缓存重建线程池任务被拒绝，active={}, queueSize={}",
                    poolExecutor.getActiveCount(), poolExecutor.getQueue().size());
            throw new RejectedExecutionException("cache rebuild executor queue is full");
        });
        return executor;
    }
}
