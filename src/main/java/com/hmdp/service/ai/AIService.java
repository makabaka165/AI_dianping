package com.hmdp.service.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * AI基础服务 - 专注于测试和健康检查
 * 职责：
 * 1. 提供AI服务健康检查和基础测试功能
 * 2. 仅用于开发调试，不参与生产业务逻辑
 * 3. 包含测试专用的原子功能方法
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "chatLanguageModel",
        streamingChatModel = "streamingChatModel",
        chatMemoryProvider = "chatMemoryProvider"
        // 注意：不包含tools，这是业务服务的专属
)
public interface AIService {

    // ========== 测试功能 ==========

    /**
     * AI服务健康检查
     */
    @SystemMessage("你是AI健康检查助手，请简单确认服务状态。")
    String healthCheck(@UserMessage String message);

    /**
     * 基础聊天测试（无记忆）
     */
    @SystemMessage("你是AI测试助手，请简洁地回答用户问题，用于测试基础对话功能。")
    String testChat(@UserMessage String message);

    /**
     * 记忆功能测试
     */
    @SystemMessage("你是AI记忆测试助手，请记住用户说的话并在后续对话中引用，用于测试记忆功能。")
    String testMemory(@MemoryId String memoryId, @UserMessage String message);

    // ========== 测试专用的原子功能 ==========

    /**
     * 情感分析测试 - 仅用于测试
     * 只返回情感倾向，不包含任何业务逻辑
     */
    @SystemMessage("你是情感分析专家，专门分析文本的情感倾向。请只分析情感，不要添加其他内容。")
    @UserMessage("请分析以下文本的情感倾向，只回答'positive', 'negative'或'neutral'，不要其他文字：\n{{content}}")
    String analyzeSentiment(@V("content") String content);

    /**
     * 关键词提取测试 - 仅用于测试
     * 只提取关键词，不包含任何业务逻辑
     */
    @SystemMessage("你是关键词提取专家，专门从文本中提取重要关键词。")
    @UserMessage("请从以下文本中提取5个最重要的关键词，只用逗号分隔，不要其他文字或符号：\n{{content}}")
    String extractKeywords(@V("content") String content);

    // ========== 开发调试功能 ==========

    /**
     * 详细情感分析测试（用于调试）
     */
    @SystemMessage("你是情感分析专家，请详细分析文本的情感倾向，用于测试目的。")
    @UserMessage("请详细分析以下文本的情感倾向，格式：'情感：正面/负面/中性，置信度：高/中/低，关键词：xxx'。\n文本：{{content}}")
    String testSentimentAnalysisDetailed(@V("content") String content);

    /**
     * 文本总结测试 - 仅用于测试
     */
    @SystemMessage("你是文本总结专家，请对提供的文本进行简洁总结，用于测试目的。")
    String testSummarize(@UserMessage String text);
}