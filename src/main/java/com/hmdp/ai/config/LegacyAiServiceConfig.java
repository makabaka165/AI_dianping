package com.hmdp.ai.config;

import com.hmdp.service.ai.AIService;
import com.hmdp.service.ai.ShopAIService;
import com.hmdp.service.ai.ShopFreeChatAIService;
import com.hmdp.service.ai.ShopRepairAIService;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit legacy proxy registration that also works from a Spring Boot executable jar. */
@Configuration
public class LegacyAiServiceConfig {

  @Bean
  @ConditionalOnMissingBean(ShopAIService.class)
  public ShopAIService shopAIService(
      @Qualifier("chatLanguageModel") ChatLanguageModel chatModel,
      @Qualifier("streamingChatModel") StreamingChatLanguageModel streamingModel,
      ChatMemoryProvider chatMemoryProvider) {
    return AiServices.builder(ShopAIService.class)
        .chatLanguageModel(chatModel)
        .streamingChatLanguageModel(streamingModel)
        .chatMemoryProvider(chatMemoryProvider)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(ShopFreeChatAIService.class)
  public ShopFreeChatAIService shopFreeChatAIService(
      @Qualifier("chatLanguageModel") ChatLanguageModel chatModel,
      @Qualifier("streamingChatModel") StreamingChatLanguageModel streamingModel,
      ChatMemoryProvider chatMemoryProvider,
      @Qualifier("platformPolicyContentRetriever") ContentRetriever contentRetriever) {
    return AiServices.builder(ShopFreeChatAIService.class)
        .chatLanguageModel(chatModel)
        .streamingChatLanguageModel(streamingModel)
        .chatMemoryProvider(chatMemoryProvider)
        .contentRetriever(contentRetriever)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(ShopRepairAIService.class)
  public ShopRepairAIService shopRepairAIService(
      @Qualifier("repairChatLanguageModel") ChatLanguageModel repairModel,
      ChatMemoryProvider chatMemoryProvider) {
    return AiServices.builder(ShopRepairAIService.class)
        .chatLanguageModel(repairModel)
        .chatMemoryProvider(chatMemoryProvider)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(AIService.class)
  public AIService aiService(
      @Qualifier("chatLanguageModel") ChatLanguageModel chatModel,
      @Qualifier("streamingChatModel") StreamingChatLanguageModel streamingModel,
      ChatMemoryProvider chatMemoryProvider) {
    return AiServices.builder(AIService.class)
        .chatLanguageModel(chatModel)
        .streamingChatLanguageModel(streamingModel)
        .chatMemoryProvider(chatMemoryProvider)
        .build();
  }
}
