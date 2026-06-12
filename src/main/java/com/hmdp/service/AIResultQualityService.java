package com.hmdp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AIResultQualityService {

    public QualityCheckResult validateContent(String content) {
        QualityCheckResult result = new QualityCheckResult();

        if (content == null || content.trim().isEmpty()) {
            result.setValid(false);
            result.setReason("内容为空");
            return result;
        }

        if (content.trim().length() < 10) {
            result.setValid(false);
            result.setReason("内容过短，可能是无效回答");
            return result;
        }

        if (containsSensitiveWords(content)) {
            result.setValid(false);
            result.setReason("内容包含敏感词");
            return result;
        }

        String lower = content.toLowerCase();
        if ((lower.contains("作为") || lower.contains("我是")) && lower.contains("ai")) {
            result.setValid(false);
            result.setReason("内容包含模型自我引用");
            return result;
        }

        if (isTemplateContent(content)) {
            result.setValid(false);
            result.setReason("内容过于模板化");
            return result;
        }

        result.setValid(true);
        return result;
    }

    public String postProcessContent(String content) {
        if (content == null) {
            return null;
        }
        String processed = content;
        processed = processed.replaceAll("(?i)作为.*?ai.*?模型.*?", "");
        processed = processed.replaceAll("(?i)我是.*?ai.*?助手.*?", "");
        processed = processed.replaceAll("根据.*?提供.*?信息.*?", "");
        processed = processed.replaceAll("基于.*?以上.*?分析.*?", "");
        processed = processed.replaceAll("\\n\\s*\\n", "\n\n");
        return processed.trim();
    }

    private boolean containsSensitiveWords(String content) {
        String[] sensitiveWords = {
                "违法", "违规", "不当", "禁止", "政治", "色情", "暴力", "赌博"
        };
        String lowerContent = content.toLowerCase();
        for (String word : sensitiveWords) {
            if (lowerContent.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isTemplateContent(String content) {
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
