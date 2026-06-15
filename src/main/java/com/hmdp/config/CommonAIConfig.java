package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;

@Configuration
@Slf4j
@EnableConfigurationProperties(RedissonProperties.class)
public class CommonAIConfig {

    private static final String DEFAULT_REDIS_HOST = "127.0.0.1";
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final int DEFAULT_REDIS_DATABASE = 0;

    @Value("${hmdp.ai.redis-health-check:true}")
    private boolean redisHealthCheckEnabled;

    @Value("${hmdp.ai.redisson-fallback:false}")
    private boolean redissonFallbackEnabled;

    @Bean
    public RedissonClient redissonClient(RedissonProperties redissonProperties,
                                         RedisProperties redisProperties) {
        ResolvedRedissonProperties resolved = resolveRedissonProperties(redissonProperties, redisProperties);
        log.info("Initializing shared RedissonClient, address={}, database={}",
                resolved.maskedAddress(), resolved.database);
        Config config = buildRedissonConfig(resolved);
        try {
            return createRedissonClient(config);
        } catch (RuntimeException e) {
            if (!redissonFallbackEnabled) {
                throw e;
            }
            log.warn("RedissonClient create failed, using no-op fallback because hmdp.ai.redisson-fallback=true", e);
            return noOpProxy(RedissonClient.class);
        }
    }

    Config buildRedissonConfig(ResolvedRedissonProperties resolved) {
        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress(resolved.address())
                .setDatabase(resolved.database)
                .setTimeout(resolved.timeoutMillis)
                .setConnectTimeout(resolved.connectTimeoutMillis)
                .setRetryAttempts(resolved.retryAttempts)
                .setRetryInterval(resolved.retryIntervalMillis);
        if (StringUtils.hasText(resolved.password)) {
            serverConfig.setPassword(resolved.password);
        }
        return config;
    }

    protected RedissonClient createRedissonClient(Config config) {
        return Redisson.create(config);
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

    @Bean
    public Object validateRedisConnections(ObjectProvider<RedissonClient> redissonClientProvider) {
        if (!redisHealthCheckEnabled) {
            log.info("skip Redis connection validation because hmdp.ai.redis-health-check=false");
            return new Object();
        }
        log.info("Validating Redis connection");
        try {
            redissonClientProvider.getObject().getBucket("health-check-session").set("ok");
            log.info("Redis connection is healthy");
        } catch (Exception e) {
            log.error("Redis connection validation failed", e);
        }
        log.info("Redis connection validation completed");
        return new Object();
    }

    ResolvedRedissonProperties resolveRedissonProperties(RedissonProperties redissonProperties,
                                                         RedisProperties redisProperties) {
        String host = firstText(redissonProperties.getHost(), redisProperties.getHost(), DEFAULT_REDIS_HOST);
        int port = firstPositive(redissonProperties.getPort(), redisProperties.getPort(), DEFAULT_REDIS_PORT);
        String password = firstText(redissonProperties.getPassword(), redisProperties.getPassword(), null);
        int database = firstNonNegative(redissonProperties.getDatabase(), redisProperties.getDatabase(), DEFAULT_REDIS_DATABASE);
        boolean ssl = Boolean.TRUE.equals(redissonProperties.getSsl());
        int timeoutMillis = positiveOrDefault(redissonProperties.getTimeoutMillis(), 3000);
        int connectTimeoutMillis = positiveOrDefault(redissonProperties.getConnectTimeoutMillis(), 3000);
        int retryAttempts = nonNegativeOrDefault(redissonProperties.getRetryAttempts(), 3);
        int retryIntervalMillis = positiveOrDefault(redissonProperties.getRetryIntervalMillis(), 1500);
        return new ResolvedRedissonProperties(host, port, password, database, ssl,
                timeoutMillis, connectTimeoutMillis, retryAttempts, retryIntervalMillis);
    }

    private String firstText(String primary, String secondary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        if (StringUtils.hasText(secondary)) {
            return secondary;
        }
        return fallback;
    }

    private int firstPositive(Integer primary, int secondary, int fallback) {
        if (primary != null && primary > 0) {
            return primary;
        }
        if (secondary > 0) {
            return secondary;
        }
        return fallback;
    }

    private int firstNonNegative(Integer primary, int secondary, int fallback) {
        if (primary != null && primary >= 0) {
            return primary;
        }
        if (secondary >= 0) {
            return secondary;
        }
        return fallback;
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int nonNegativeOrDefault(Integer value, int fallback) {
        return value == null || value < 0 ? fallback : value;
    }

    static class ResolvedRedissonProperties {
        private final String host;
        private final int port;
        private final String password;
        private final int database;
        private final boolean ssl;
        private final int timeoutMillis;
        private final int connectTimeoutMillis;
        private final int retryAttempts;
        private final int retryIntervalMillis;

        private ResolvedRedissonProperties(String host, int port, String password, int database, boolean ssl,
                                           int timeoutMillis, int connectTimeoutMillis,
                                           int retryAttempts, int retryIntervalMillis) {
            this.host = host;
            this.port = port;
            this.password = password;
            this.database = database;
            this.ssl = ssl;
            this.timeoutMillis = timeoutMillis;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.retryAttempts = retryAttempts;
            this.retryIntervalMillis = retryIntervalMillis;
        }

        private String address() {
            return (ssl ? "rediss://" : "redis://") + host + ":" + port;
        }

        private String maskedAddress() {
            return address();
        }
    }
}
