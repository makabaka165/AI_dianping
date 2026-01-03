package com.hmdp.service.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.service.*;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 店铺智能分析服务 - 面向真实业务场景
 * 职责：
 * 1. 提供专业的店铺咨询服务
 * 2. 整合多种AI分析能力
 * 3. 支持记忆功能的智能对话
 * 4. 提供标准化的工具调用接口
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "chatLanguageModel",
        streamingChatModel = "streamingChatModel",
        chatMemoryProvider = "chatMemoryProvider",
        contentRetriever = "contentRetriever", // 添加内容检索器以启用RAG功能
        tools = {
                "shopTool", // 注入所有店铺工具
                "documentManagementTool" // 注入文档管理工具
        },
        maxToolCalls = 10 // 限制每个对话周期内的工具调用次数，避免因推理过程失控导致的重复调用问题
)
public interface ShopAIService {

    // ========== 智能对话功能 ==========

    /**
     * 标准智能对话（支持记忆和RAG）
     * 用户可以自由表达需求，AI会自动选择合适的工具或检索相关知识
     */
    @SystemMessage("你是专业的店铺咨询顾问\"小店助手\"。你有以下工具可以使用：\n" +
            "\n" +
            "🔧 **可用工具**：\n" +
            "- getShopBasicSummary: 获取店铺基础总结信息\n" +
            "- askAboutShop: 回答关于特定店铺的问题  \n" +
            "- compareShops: 对比两个店铺的优缺点\n" +
            "- recommendShops: 根据用户偏好推荐店铺\n" +
            "- clearShopQAMemory: 清除店铺问答记忆\n" +
            "- getMemoryStats: 获取记忆统计信息\n" +
            "- checkShopExists: 检查店铺是否存在并获取评价数量\n" +
            "- listAllDocuments: 列出所有文档的元数据信息\n" +
            "- listDocumentsByQualityScore: 根据质量评分范围查找文档\n" +
            "- listDocumentsByStatus: 根据状态查找文档\n" +
            "- getDocumentDetails: 获取文档详细信息\n" +
            "- getDocumentStatistics: 获取系统中文档统计信息\n" +
            "\n" +
            "💡 **工作原则**：\n" +
            "1. 根据用户问题智能选择最合适的工具\n" +
            "2. 每个问题最多调用1个工具\n" +
            "3. 工具调用后基于结果提供专业分析\n" +
            "4. 如果工具调用失败，友好地告知用户\n" +
            "5. 始终基于真实数据给出建议\n" +
            "6. 回答问题时优先参考高质量文档内容\n" +
            "7. 避免重复调用相同工具\n" +
            "8. 对于相同或相似请求，优先使用已有结果\n" +
            "\n" +
            "请提供准确、专业的店铺咨询服务！")
    String chat(@MemoryId String memoryId, @UserMessage String message);

    @SystemMessage("你是专业的店铺咨询顾问\"小店助手\"。你有以下工具可以使用：\n" +
            "\n" +
            "🔧 **可用工具**：\n" +
            "- getShopBasicSummary: 获取店铺基础总结信息\n" +
            "- askAboutShop: 回答关于特定店铺的问题  \n" +
            "- compareShops: 对比两个店铺的优缺点\n" +
            "- recommendShops: 根据用户偏好推荐店铺\n" +
            "- clearShopQAMemory: 清除店铺问答记忆\n" +
            "- getMemoryStats: 获取记忆统计信息\n" +
            "- checkShopExists: 检查店铺是否存在并获取评价数量\n" +
            "- listAllDocuments: 列出所有文档的元数据信息\n" +
            "- listDocumentsByQualityScore: 根据质量评分范围查找文档\n" +
            "- listDocumentsByStatus: 根据状态查找文档\n" +
            "- getDocumentDetails: 获取文档详细信息\n" +
            "- getDocumentStatistics: 获取系统中文档统计信息\n" +
            "\n" +
            "💡 **工作原则**：\n" +
            "1. 根据用户问题智能选择最合适的工具\n" +
            "2. 每个问题最多调用1个工具\n" +
            "3. 工具调用后基于结果提供专业分析\n" +
            "4. 如果工具调用失败，友好地告知用户\n" +
            "5. 始终基于真实数据给出建议\n" +
            "6. 回答问题时优先参考高质量文档内容\n" +
            "7. 避免重复调用相同工具\n" +
            "8. 对于相同或相似请求，优先使用已有结果\n" +
            "\n" +
            "请提供准确、专业的店铺咨询服务！")
    Flux<String> chatStream(@MemoryId String memoryId, @UserMessage String message);

    // ========== 原子分析功能（不使用Tool） ==========

    @SystemMessage("你是专业的店铺数据分析师，请基于提供的评价数据进行客观分析。")
    String analyzeShopData(@MemoryId String memoryId, @UserMessage String analysisPrompt);

    @SystemMessage("你是情感分析专家。请分析文本的情感倾向，只回答'positive', 'negative'或'neutral'。")
    String analyzeSentiment(@UserMessage String content);

    @SystemMessage("你是关键词提取专家。请从文本中提取5个最重要的关键词，用逗号分隔。")
    String extractKeywords(@UserMessage String content);

    @SystemMessage("你是专业的店铺分析师。请综合分析用户评价并生成150-300字的专业总结。")
    String generateSummary(@UserMessage String summaryPrompt);
    
    /**
     * 基于RAG的知识问答功能
     * 会自动检索相关文档内容并基于内容回答问题
     */
    @SystemMessage("你是一个专业的客服助手，能够基于店铺相关的文档内容回答用户问题。" +
            "请优先参考高质量的文档内容，提供准确、专业的回答。")
    String ragQuery(@UserMessage String query);
}