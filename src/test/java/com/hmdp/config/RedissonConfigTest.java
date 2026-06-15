package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RedissonConfigTest {

    @Test
    void resolveShouldFallbackToSpringRedisHostPortAndDefaults() {
        CommonAIConfig config = new CommonAIConfig();
        RedissonProperties redissonProperties = new RedissonProperties();
        RedisProperties redisProperties = new RedisProperties();
        redisProperties.setHost("redis.local");
        redisProperties.setPort(6381);
        redisProperties.setDatabase(2);

        CommonAIConfig.ResolvedRedissonProperties resolved =
                config.resolveRedissonProperties(redissonProperties, redisProperties);
        Config redissonConfig = config.buildRedissonConfig(resolved);
        SingleServerConfig singleServerConfig = singleServerConfig(redissonConfig);

        assertThat(singleServerConfig.getAddress()).isEqualTo("redis://redis.local:6381");
        assertThat(singleServerConfig.getDatabase()).isEqualTo(2);
        assertThat(singleServerConfig.getTimeout()).isEqualTo(3000);
        assertThat(singleServerConfig.getConnectTimeout()).isEqualTo(3000);
        assertThat(singleServerConfig.getRetryAttempts()).isEqualTo(3);
        assertThat(singleServerConfig.getRetryInterval()).isEqualTo(1500);
    }

    @Test
    void resolveShouldUseExplicitRedissonPropertiesAndSslAddress() {
        CommonAIConfig config = new CommonAIConfig();
        RedissonProperties redissonProperties = new RedissonProperties();
        redissonProperties.setHost("secure.redis");
        redissonProperties.setPort(6380);
        redissonProperties.setPassword("secret");
        redissonProperties.setDatabase(5);
        redissonProperties.setSsl(true);
        redissonProperties.setTimeoutMillis(4000);
        redissonProperties.setConnectTimeoutMillis(5000);
        redissonProperties.setRetryAttempts(4);
        redissonProperties.setRetryIntervalMillis(600);

        CommonAIConfig.ResolvedRedissonProperties resolved =
                config.resolveRedissonProperties(redissonProperties, new RedisProperties());
        SingleServerConfig singleServerConfig = singleServerConfig(config.buildRedissonConfig(resolved));

        assertThat(singleServerConfig.getAddress()).isEqualTo("rediss://secure.redis:6380");
        assertThat(singleServerConfig.getPassword()).isEqualTo("secret");
        assertThat(singleServerConfig.getDatabase()).isEqualTo(5);
        assertThat(singleServerConfig.getTimeout()).isEqualTo(4000);
        assertThat(singleServerConfig.getConnectTimeout()).isEqualTo(5000);
        assertThat(singleServerConfig.getRetryAttempts()).isEqualTo(4);
        assertThat(singleServerConfig.getRetryInterval()).isEqualTo(600);
    }

    @Test
    void buildRedissonConfigShouldNotSetBlankPassword() {
        CommonAIConfig config = new CommonAIConfig();
        RedissonProperties redissonProperties = new RedissonProperties();
        redissonProperties.setPassword(" ");

        CommonAIConfig.ResolvedRedissonProperties resolved =
                config.resolveRedissonProperties(redissonProperties, new RedisProperties());

        assertThat(singleServerConfig(config.buildRedissonConfig(resolved)).getPassword()).isNull();
    }

    @Test
    void redissonClientShouldFailFastWhenFallbackDisabled() {
        TestableCommonAIConfig config = new TestableCommonAIConfig(true);
        ReflectionTestUtils.setField(config, "redissonFallbackEnabled", false);

        assertThatThrownBy(() -> config.redissonClient(new RedissonProperties(), new RedisProperties()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("redis down");
    }

    @Test
    void redissonClientShouldReturnNoOpProxyWhenFallbackEnabled() {
        TestableCommonAIConfig config = new TestableCommonAIConfig(true);
        ReflectionTestUtils.setField(config, "redissonFallbackEnabled", true);

        RedissonClient client = config.redissonClient(new RedissonProperties(), new RedisProperties());

        assertThat(client).isNotNull();
        assertThat(client.getClass().getName()).contains("$Proxy");
    }

    @Test
    void redissonCreationLogicShouldNotHardcodeLocalhostAddress() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/hmdp/config/CommonAIConfig.java"),
                StandardCharsets.UTF_8);

        assertThat(source).doesNotContain("redis://127.0.0.1:6379");
    }

    private static class TestableCommonAIConfig extends CommonAIConfig {
        private final boolean throwOnCreate;

        private TestableCommonAIConfig(boolean throwOnCreate) {
            this.throwOnCreate = throwOnCreate;
        }

        @Override
        protected RedissonClient createRedissonClient(Config config) {
            if (throwOnCreate) {
                throw new RuntimeException("redis down");
            }
            return mock(RedissonClient.class);
        }
    }

    private SingleServerConfig singleServerConfig(Config config) {
        return (SingleServerConfig) ReflectionTestUtils.getField(config, "singleServerConfig");
    }
}
