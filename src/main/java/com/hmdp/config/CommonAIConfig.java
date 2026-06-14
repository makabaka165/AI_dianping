package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;

@Configuration
@Slf4j
public class CommonAIConfig {

    @Value("${hmdp.ai.redis-health-check:true}")
    private boolean redisHealthCheckEnabled;

    @Value("${hmdp.ai.redisson-fallback:false}")
    private boolean redissonFallbackEnabled;

    @Bean
    public RedissonClient redissonClient() {
        log.info("Initializing shared RedissonClient");
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        try {
            return Redisson.create(config);
        } catch (RuntimeException e) {
            if (!redissonFallbackEnabled) {
                throw e;
            }
            log.warn("RedissonClient create failed, using no-op fallback because hmdp.ai.redisson-fallback=true", e);
            return noOpProxy(RedissonClient.class);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T noOpProxy(Class<T> type) {
        InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            if ("toString".equals(methodName)) {
                return "NoOp" + type.getSimpleName();
            }
            if ("tryLock".equals(methodName)) {
                return true;
            }
            if ("isHeldByCurrentThread".equals(methodName)) {
                return true;
            }
            if ("isShutdown".equals(methodName) || "isShuttingDown".equals(methodName)) {
                return false;
            }
            if ("delete".equals(methodName)) {
                return true;
            }
            if ("getKeysByPattern".equals(methodName)) {
                return Collections.emptyList();
            }

            Class<?> returnType = method.getReturnType();
            if (Void.TYPE.equals(returnType)) {
                return null;
            }
            if (Boolean.TYPE.equals(returnType)) {
                return false;
            }
            if (Integer.TYPE.equals(returnType) || Long.TYPE.equals(returnType)
                    || Short.TYPE.equals(returnType) || Byte.TYPE.equals(returnType)) {
                return 0;
            }
            if (Double.TYPE.equals(returnType) || Float.TYPE.equals(returnType)) {
                return 0D;
            }
            if (Iterable.class.isAssignableFrom(returnType)) {
                return Collections.emptyList();
            }
            if (returnType.isInterface()) {
                return noOpProxy((Class<Object>) returnType);
            }
            return null;
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @PostConstruct
    public void validateRedisConnections() {
        if (!redisHealthCheckEnabled) {
            log.info("skip Redis connection validation because hmdp.ai.redis-health-check=false");
            return;
        }
        log.info("Validating Redis connection");
        try {
            redissonClient().getBucket("health-check-session").set("ok");
            log.info("Redis (6379) connection is healthy");
        } catch (Exception e) {
            log.error("Redis (6379) connection validation failed", e);
        }
        log.info("Redis connection validation completed");
    }
}
