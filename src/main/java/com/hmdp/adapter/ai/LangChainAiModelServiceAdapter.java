package com.hmdp.adapter.ai;

import com.hmdp.ai.port.AiModelServicePort;
import com.hmdp.service.ai.ShopAIService;
import com.hmdp.service.ai.ShopFreeChatAIService;
import com.hmdp.service.ai.ShopRepairAIService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;

@Component
public class LangChainAiModelServiceAdapter implements AiModelServicePort {

    @Resource
    private ShopAIService shopAIService;

    @Resource
    private ShopFreeChatAIService shopFreeChatAIService;

    @Resource
    private ShopRepairAIService shopRepairAIService;

    @Override
    public String generateStructuredAnalysis(String prompt) {
        return shopAIService.generateStructuredAnalysis(prompt);
    }

    @Override
    public String repairStructuredAnalysis(String prompt) {
        return shopRepairAIService.generateStructuredAnalysis(prompt);
    }

    @Override
    public String analyzeShopData(String memoryId, String prompt) {
        return shopAIService.analyzeShopData(memoryId, prompt);
    }

    @Override
    public String repairAnalyzeShopData(String memoryId, String prompt) {
        return shopRepairAIService.analyzeShopData(memoryId, prompt);
    }

    @Override
    public String classifyIntent(String prompt) {
        return shopAIService.classifyIntent(prompt);
    }

    @Override
    public String chat(String memoryId, String prompt) {
        return shopFreeChatAIService.chat(memoryId, prompt);
    }

    @Override
    public String repairChat(String memoryId, String prompt) {
        return shopRepairAIService.chat(memoryId, prompt);
    }

    @Override
    public Flux<String> chatStream(String memoryId, String prompt) {
        return shopAIService.chatStream(memoryId, prompt);
    }

    @Override
    public Flux<String> freeChatStream(String memoryId, String message) {
        return shopFreeChatAIService.chatStream(memoryId, message);
    }
}
