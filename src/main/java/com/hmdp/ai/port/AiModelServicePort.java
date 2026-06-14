package com.hmdp.ai.port;

import reactor.core.publisher.Flux;

public interface AiModelServicePort {

    String generateStructuredAnalysis(String prompt);

    String repairStructuredAnalysis(String prompt);

    String analyzeShopData(String memoryId, String prompt);

    String repairAnalyzeShopData(String memoryId, String prompt);

    String classifyIntent(String prompt);

    String chat(String memoryId, String prompt);

    String repairChat(String memoryId, String prompt);

    Flux<String> chatStream(String memoryId, String prompt);

    Flux<String> freeChatStream(String memoryId, String message);
}
