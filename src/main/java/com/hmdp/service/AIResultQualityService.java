package com.hmdp.service;

import com.hmdp.utils.LocalCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * AI结果质量验证和后处理服务
 * 负责验证AI生成内容的质量并进行必要的后处理
 */
@Service
@Slf4j
public class AIResultQualityService {

    @Autowired
    private LocalCacheManager localCacheManager;

    /**
     * 验证AI生成内容的质量
     * @param content AI生成的内容
     * @return 验证结果
     */
    public QualityCheckResult validateContent(String content) {
        QualityCheckResult result = new QualityCheckResult();
        
        if (content == null) {
            result.setValid(false);
            result.setReason("内容为空");
            return result;
        }

        // 检查内容长度
        if (content.trim().length() < 10) {
            result.setValid(false);
            result.setReason("内容过短，可能为AI生成的无效内容");
            return result;
        }

        // 检查是否包含敏感词
        if (containsSensitiveWords(content)) {
            result.setValid(false);
            result.setReason("内容包含敏感词");
            return result;
        }

        // 检查是否包含AI模型的自我引用
        if (content.toLowerCase().contains("作为") && content.toLowerCase().contains("ai")) {
            result.setValid(false);
            result.setReason("内容包含AI模型自我引用");
            return result;
        }

        // 检查内容是否过于模板化
        if (isTemplateContent(content)) {
            result.setValid(false);
            result.setReason("内容过于模板化，可能为AI生成的通用回复");
            return result;
        }

        result.setValid(true);
        return result;
    }

    /**
     * 对AI生成的内容进行后处理
     * @param content AI生成的内容
     * @return 处理后的内容
     */
    public String postProcessContent(String content) {
        if (content == null) {
            return null;
        }

        String processedContent = content;

        // 移除AI模型的自我引用
        processedContent = processedContent.replaceAll("(?i)作为.*?ai.*?模型.*?|我是.*?ai.*?助手.*?", "");

        // 移除过于模板化的表述
        processedContent = processedContent.replaceAll("(?i)根据.*?提供.*?信息.*?|基于.*?以上.*?分析.*?", "");

        // 清理多余的空行和空格
        processedContent = processedContent.replaceAll("\\n\\s*\\n", "\n\n");
        processedContent = processedContent.trim();

        return processedContent;
    }

    /**
     * 检查是否包含敏感词
     * @param content 内容
     * @return 是否包含敏感词
     */
    private boolean containsSensitiveWords(String content) {
        // 这里可以定义敏感词列表
        String[] sensitiveWords = {
            "违法", "违规", "不当", "禁止", "限制", "政治", "色情", "暴力", "赌博"
        };

        String lowerContent = content.toLowerCase();
        for (String word : sensitiveWords) {
            if (lowerContent.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查内容是否过于模板化
     * @param content 内容
     * @return 是否模板化
     */
    private boolean isTemplateContent(String content) {
        // 检查是否包含常见的模板化表述
        String[] templatePhrases = {
            "根据您提供的信息",
            "基于以上信息",
            "根据上述内容",
            "希望以上信息对您有帮助",
            "如有需要请继续提问",
            "请告诉我更多细节"
        };

        String lowerContent = content.toLowerCase();
        for (String phrase : templatePhrases) {
            if (lowerContent.contains(phrase.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 质量检查结果类
     */
    public static class QualityCheckResult {
        private boolean valid;
        private String reason;

        public boolean isValid() {
            return valid;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}