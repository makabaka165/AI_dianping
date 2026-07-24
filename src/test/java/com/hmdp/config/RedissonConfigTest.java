package com.hmdp.config;

import com.hmdp.ai.infrastructure.redis.AiRedisConfiguration;
import com.hmdp.ai.infrastructure.redis.AiRedisProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RedissonConfigTest {
    @Test
    void configuresBusinessAndMemoryClientsWithExplicitNamesAndNoPrimary() throws Exception {
        Method business = AiRedisConfiguration.class.getMethod("businessRedissonClient", AiRedisProperties.class);
        Method memory = AiRedisConfiguration.class.getMethod("memoryRedissonClient", AiRedisProperties.class);
        assertThat(business.getAnnotation(Bean.class).name()).containsExactly("businessRedissonClient");
        assertThat(memory.getAnnotation(Bean.class).name()).containsExactly("memoryRedissonClient");
        assertThat(business.isAnnotationPresent(Primary.class)).isFalse();
        assertThat(memory.isAnnotationPresent(Primary.class)).isFalse();
    }

    @Test
    void makesBusinessConnectionFactoryPrimaryForUnqualifiedInfrastructureConsumers() throws Exception {
        Method business = AiRedisConfiguration.class.getMethod(
                "businessRedisConnectionFactory", AiRedisProperties.class);
        Method vector = AiRedisConfiguration.class.getMethod(
                "vectorRedisConnectionFactory", AiRedisProperties.class);

        assertThat(business.isAnnotationPresent(Primary.class)).isTrue();
        assertThat(vector.isAnnotationPresent(Primary.class)).isFalse();
    }

    @Test
    void buildsSslPasswordAndRetryConfiguration() {
        TestableConfiguration configuration = new TestableConfiguration();
        AiRedisProperties.Endpoint endpoint = new AiRedisProperties.Endpoint("secure.redis", 6380, 5);
        endpoint.setSsl(true); endpoint.setPassword("secret"); endpoint.setTimeoutMillis(4000);
        endpoint.setConnectTimeoutMillis(5000); endpoint.setRetryAttempts(4); endpoint.setRetryIntervalMillis(600);
        SingleServerConfig server = (SingleServerConfig) ReflectionTestUtils.getField(configuration.expose(endpoint), "singleServerConfig");
        assertThat(server.getAddress()).isEqualTo("rediss://secure.redis:6380");
        assertThat(server.getPassword()).isEqualTo("secret");
        assertThat(server.getDatabase()).isEqualTo(5);
        assertThat(server.getRetryAttempts()).isEqualTo(4);
    }

    @Test
    void rejectsInvalidEndpointBeforeClientCreation() {
        AiRedisProperties properties = new AiRedisProperties();
        properties.getBusiness().setPort(0);
        assertThatThrownBy(() -> new TestableConfiguration().businessRedissonClient(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void springContextContainsExactlyTwoExplicitlyNamedRedissonClients() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestableConfiguration.class)) {
            assertThat(context.getBeansOfType(RedissonClient.class).keySet())
                    .containsExactlyInAnyOrder("businessRedissonClient", "memoryRedissonClient");
        }
    }

    @Configuration
    @EnableConfigurationProperties(AiRedisProperties.class)
    static class TestableConfiguration extends AiRedisConfiguration {
        Config expose(AiRedisProperties.Endpoint endpoint) { return buildConfig(endpoint); }
        @Override protected RedissonClient createRedissonClient(Config config) { return mock(RedissonClient.class); }
    }
}
