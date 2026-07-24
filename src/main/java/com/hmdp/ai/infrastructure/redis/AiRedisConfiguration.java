package com.hmdp.ai.infrastructure.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(AiRedisProperties.class)
public class AiRedisConfiguration {

    @Bean(name = "businessRedissonClient", destroyMethod = "shutdown")
    public RedissonClient businessRedissonClient(AiRedisProperties properties) {
        return createClient("business", properties.getBusiness());
    }

    @Bean(name = "memoryRedissonClient", destroyMethod = "shutdown")
    public RedissonClient memoryRedissonClient(AiRedisProperties properties) {
        return createClient("memory", properties.getMemory());
    }

    @Bean(name = "vectorRedisConnectionFactory", destroyMethod = "destroy")
    public LettuceConnectionFactory vectorRedisConnectionFactory(AiRedisProperties properties) {
        return createConnectionFactory("vector", properties.getVector());
    }

    @Bean(name = "businessRedisConnectionFactory", destroyMethod = "destroy")
    @Primary
    public LettuceConnectionFactory businessRedisConnectionFactory(AiRedisProperties properties) {
        return createConnectionFactory("business", properties.getBusiness());
    }

    private LettuceConnectionFactory createConnectionFactory(String name, AiRedisProperties.Endpoint endpoint) {
        endpoint.validate(name);
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(endpoint.getHost(), endpoint.getPort());
        configuration.setDatabase(endpoint.getDatabase());
        if (StringUtils.hasText(endpoint.getPassword())) {
            configuration.setPassword(RedisPassword.of(endpoint.getPassword()));
        }
        return new LettuceConnectionFactory(configuration);
    }

    @Bean(name = "stringRedisTemplate")
    public StringRedisTemplate stringRedisTemplate(
            @Qualifier("businessRedisConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    private RedissonClient createClient(String name, AiRedisProperties.Endpoint endpoint) {
        endpoint.validate(name);
        return createRedissonClient(buildConfig(endpoint));
    }

    protected Config buildConfig(AiRedisProperties.Endpoint endpoint) {
        Config config = new Config();
        String scheme = endpoint.isSsl() ? "rediss://" : "redis://";
        SingleServerConfig server = config.useSingleServer()
                .setAddress(scheme + endpoint.getHost() + ":" + endpoint.getPort())
                .setDatabase(endpoint.getDatabase())
                .setTimeout(endpoint.getTimeoutMillis())
                .setConnectTimeout(endpoint.getConnectTimeoutMillis())
                .setRetryAttempts(endpoint.getRetryAttempts())
                .setRetryInterval(endpoint.getRetryIntervalMillis());
        if (StringUtils.hasText(endpoint.getPassword())) {
            server.setPassword(endpoint.getPassword());
        }
        return config;
    }

    protected RedissonClient createRedissonClient(Config config) {
        return Redisson.create(config);
    }
}
