package com.hmdp.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hmdp.HmDianPingApplication;
import com.hmdp.ai.integration.support.IntegrationMySqlContainer;
import com.hmdp.service.ai.AIService;
import com.hmdp.service.ai.ShopAIService;
import com.hmdp.service.ai.ShopFreeChatAIService;
import com.hmdp.service.ai.ShopRepairAIService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = HmDianPingApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "rag.enabled=false",
      "rag.data.auto-import=false",
      "hmdp.ai.task.enabled=false",
      "hmdp.ai.knowledge.recovery-enabled=false",
      "hmdp.voucher.order.worker-threads=1",
      "hmdp.voucher.order.close-worker-threads=1",
      "hmdp.voucher.order.stream-health-check-enabled=false",
      "langchain4j.open-ai.chat-model.api-key=context-smoke-key",
      "langchain4j.open-ai.streaming-chat-model.api-key=context-smoke-key",
      "langchain4j.open-ai.embedding-model.api-key=context-smoke-key"
    })
class ApplicationContextSmokeIT {
  @Container
  static final IntegrationMySqlContainer MYSQL = new IntegrationMySqlContainer();

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.redis.host", REDIS::getHost);
    registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("hmdp.ai.redis.business.host", REDIS::getHost);
    registry.add("hmdp.ai.redis.business.port", () -> REDIS.getMappedPort(6379));
    registry.add("hmdp.ai.redis.memory.host", REDIS::getHost);
    registry.add("hmdp.ai.redis.memory.port", () -> REDIS.getMappedPort(6379));
    registry.add("hmdp.ai.redis.vector.host", REDIS::getHost);
    registry.add("hmdp.ai.redis.vector.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired private ApplicationContext context;

  @Test
  void fullApplicationContextStartsWithExactlyOneLegacyAiProxyPerInterface() {
    assertThat(context.getBeansOfType(ShopAIService.class)).hasSize(1);
    assertThat(context.getBeansOfType(ShopFreeChatAIService.class)).hasSize(1);
    assertThat(context.getBeansOfType(ShopRepairAIService.class)).hasSize(1);
    assertThat(context.getBeansOfType(AIService.class)).hasSize(1);
  }
}
