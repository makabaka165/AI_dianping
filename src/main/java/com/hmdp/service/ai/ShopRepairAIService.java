package com.hmdp.service.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * Low-temperature model adapter used only for one-shot repair after quality rejection.
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "repairChatLanguageModel",
        chatMemoryProvider = "chatMemoryProvider"
)
public interface ShopRepairAIService {

    @SystemMessage("You repair a rejected shop analysis answer. Be conservative, concise and evidence-bound. "
            + "Never invent shop facts, prices, addresses, or ratings.")
    String analyzeShopData(@MemoryId String memoryId, @UserMessage String repairPrompt);

    @SystemMessage("You repair rejected structured shop analysis. Output strict JSON only, no markdown. "
            + "Required fields: summary, sentiment, keywords, pros, cons, confidence, evidenceIds. "
            + "evidenceIds must reference only provided blogId values.")
    String generateStructuredAnalysis(@UserMessage String repairPrompt);

    @SystemMessage("You repair a rejected free-chat response. Do not call tools. "
            + "Only explain capabilities, ask for missing parameters, or give low-risk guidance.")
    String chat(@MemoryId String memoryId, @UserMessage String repairPrompt);
}
