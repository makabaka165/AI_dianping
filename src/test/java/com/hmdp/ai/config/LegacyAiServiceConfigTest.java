package com.hmdp.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hmdp.service.ai.AIService;
import com.hmdp.service.ai.ShopAIService;
import com.hmdp.service.ai.ShopFreeChatAIService;
import com.hmdp.service.ai.ShopRepairAIService;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class LegacyAiServiceConfigTest {

  @Test
  void registersEachLegacyAiServiceExactlyOnce() {
    try (AnnotationConfigApplicationContext context = newContext()) {
      assertThat(context.getBeansOfType(AIService.class)).hasSize(1);
      assertThat(context.getBeansOfType(ShopAIService.class)).hasSize(1);
      assertThat(context.getBeansOfType(ShopFreeChatAIService.class)).hasSize(1);
      assertThat(context.getBeansOfType(ShopRepairAIService.class)).hasSize(1);
    }
  }

  @Test
  void backsOffWhenAnApplicationAlreadyProvidesTheLegacyService() {
    ShopAIService existing = mock(ShopAIService.class);
    try (AnnotationConfigApplicationContext context = newContext(existing)) {
      assertThat(context.getBeansOfType(ShopAIService.class)).hasSize(1);
      assertThat(context.getBean(ShopAIService.class)).isSameAs(existing);
    }
  }

  private AnnotationConfigApplicationContext newContext() {
    return newContext(null);
  }

  private AnnotationConfigApplicationContext newContext(ShopAIService existing) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean("chatLanguageModel", ChatLanguageModel.class, () -> mock(ChatLanguageModel.class));
    context.registerBean(
        "repairChatLanguageModel", ChatLanguageModel.class, () -> mock(ChatLanguageModel.class));
    context.registerBean(
        "streamingChatModel",
        StreamingChatLanguageModel.class,
        () -> mock(StreamingChatLanguageModel.class));
    context.registerBean("chatMemoryProvider", ChatMemoryProvider.class, () -> mock(ChatMemoryProvider.class));
    context.registerBean(
        "platformPolicyContentRetriever",
        ContentRetriever.class,
        () -> mock(ContentRetriever.class));
    if (existing != null) {
      context.registerBean("existingShopAIService", ShopAIService.class, () -> existing);
    }
    context.register(LegacyAiServiceConfig.class);
    context.refresh();
    return context;
  }
}
