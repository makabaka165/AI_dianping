package com.hmdp.ai.fallback;

import com.hmdp.ai.port.ReviewDataPort;
import com.hmdp.dto.ai.ReviewDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI服务降级实现
 * 当AI服务不可用时，提供基于规则的传统分析方案
 */
@Service
@Slf4j
public class AIFallbackService {

    @Autowired
    private ReviewDataPort reviewDataPort;

    /**
     * 生成店铺总结的降级实现
     * @param shopId 店铺ID
     * @return 店铺总结
     */
    public String generateSummaryFallback(Long shopId) {
        try {
            List<ReviewDoc> blogs = reviewDataPort.findReviewsByShopId(shopId);
            if (blogs.isEmpty()) {
                return "该店铺暂无评价数据";
            }

            // 简单的规则基础分析
            StringBuilder summary = new StringBuilder();
            summary.append("店铺").append(shopId).append("共有").append(blogs.size()).append("条评价。\n");

            // 统计高频词汇
            String commonTopics = getCommonTopics(blogs);
            if (!commonTopics.isEmpty()) {
                summary.append("用户主要关注：").append(commonTopics).append("。\n");
            }

            // 获取最新评价
            if (!blogs.isEmpty()) {
                String latestContent = blogs.get(0).getContent();
                if (latestContent.length() > 50) {
                    latestContent = latestContent.substring(0, 50) + "...";
                }
                summary.append("最新评价：").append(latestContent);
            }

            return summary.toString();
        } catch (Exception e) {
            log.error("生成店铺总结降级方案失败", e);
            return "抱歉，暂时无法生成店铺总结，请稍后重试。";
        }
    }

    /**
     * 关键词提取的降级实现
     * @param content 待分析内容
     * @return 关键词
     */
    public String extractKeywordsFallback(String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                return "";
            }

            // 简单的关键词提取：提取长度在2-8个字符之间的词
            String[] words = content.replaceAll("[，。！？、]", " ").split("\\s+");
            StringBuilder result = new StringBuilder();

            for (String word : words) {
                word = word.trim();
                if (word.length() >= 2 && word.length() <= 8 && !isCommonWord(word)) {
                    if (result.length() > 0) {
                        result.append(",");
                    }
                    result.append(word);
                    
                    if (result.length() > 50) { // 限制关键词数量
                        break;
                    }
                }
            }

            return result.toString();
        } catch (Exception e) {
            log.error("关键词提取降级方案失败", e);
            return "服务,评价,质量,价格,环境";
        }
    }

    /**
     * 数据分析的降级实现
     * @param memoryId 记忆ID
     * @param analysisPrompt 分析提示
     * @return 分析结果
     */
    public String analyzeShopDataFallback(String memoryId, String analysisPrompt) {
        try {
            return "当前AI服务不可用，已切换到降级模式。暂时无法生成完整分析，请稍后重试。";
        } catch (Exception e) {
            log.error("店铺数据分析降级方案失败", e);
            return "抱歉，当前AI服务不可用，暂时无法进行详细分析。";
        }
    }

    /**
     * 获取常见话题的降级实现
     */
    private String getCommonTopics(List<ReviewDoc> blogs) {
        // 简单实现：提取所有评价中的常见词
        StringBuilder topics = new StringBuilder();
        int count = 0;
        
        for (ReviewDoc blog : blogs) {
            String content = blog.getContent();
            // 简单提取一些可能的关键词
            if (content.contains("服务")) {
                if (topics.length() > 0) topics.append("、");
                topics.append("服务");
                count++;
            }
            if (content.contains("环境") && count < 5) {
                if (topics.length() > 0) topics.append("、");
                topics.append("环境");
                count++;
            }
            if (content.contains("价格") && count < 5) {
                if (topics.length() > 0) topics.append("、");
                topics.append("价格");
                count++;
            }
            if (content.contains("味道") && count < 5) {
                if (topics.length() > 0) topics.append("、");
                topics.append("味道");
                count++;
            }
            if (content.contains("菜品") && count < 5) {
                if (topics.length() > 0) topics.append("、");
                topics.append("菜品");
                count++;
            }
            if (count >= 5) break;
        }
        
        return topics.toString();
    }

    /**
     * 判断是否为常用词（停用词）
     */
    private boolean isCommonWord(String word) {
        String[] commonWords = {"的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也", "很"};
        for (String commonWord : commonWords) {
            if (commonWord.equals(word)) {
                return true;
            }
        }
        return false;
    }
}
