package com.hmdp.ai.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@Slf4j
public class AIResultQualityService {

    private static final Pattern AI_SELF_REFERENCE = Pattern.compile(
            "(?i)(作为\\s*(ai|人工智能|大语言模型)|我是\\s*(ai|人工智能|大语言模型)|as\\s+an\\s+ai)");
    private static final Pattern CLEARLY_UNSAFE = Pattern.compile(
            "(违法犯罪指导|规避监管|色情交易|赌博平台|暴力伤害教程|仇恨煽动)");
    private static final Pattern HALLUCINATION_RISK = Pattern.compile(
            "(我保证|绝对最好|官方承诺|无需看商家规则)");
    private static final Pattern TEMPLATE_LINE = Pattern.compile(
            "(?m)^\\s*(希望以上信息对您有帮助|如有需要请继续提问|请告诉我更多细节)[。！!\\s]*$");

    public QualityCheckResult validateContent(String content) {
        return validateContent(content, true);
    }

    public QualityCheckResult validateContentFragment(String content) {
        return validateContent(content, false);
    }

    private QualityCheckResult validateContent(String content, boolean requireSubstantiveLength) {
        QualityCheckResult result = new QualityCheckResult();

        if (content == null || content.trim().isEmpty()) {
            result.setValid(false);
            result.setReason("内容为空");
            return result;
        }

        if (requireSubstantiveLength && content.trim().length() < 10) {
            result.setValid(false);
            result.setReason("内容过短，可能是无效回答");
            return result;
        }

        if (containsClearlyUnsafeContent(content)) {
            result.setValid(false);
            result.setReason("内容包含明显不合规表达");
            return result;
        }

        if (AI_SELF_REFERENCE.matcher(content).find()) {
            result.setValid(false);
            result.setReason("内容包含模型自我引用");
            return result;
        }

        if (HALLUCINATION_RISK.matcher(content).find()) {
            result.setValid(false);
            result.setReason("内容包含过度确定或越界承诺");
            return result;
        }

        if (isPureTemplateContent(content)) {
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
        processed = AI_SELF_REFERENCE.matcher(processed).replaceAll("");
        processed = TEMPLATE_LINE.matcher(processed).replaceAll("");
        processed = processed.replaceAll("\\n\\s*\\n", "\n\n");
        return processed.trim();
    }

    private boolean containsClearlyUnsafeContent(String content) {
        return CLEARLY_UNSAFE.matcher(content).find();
    }

    private boolean isPureTemplateContent(String content) {
        String normalized = content.replaceAll("[\\s。！!，,、.]+", "");
        return "希望以上信息对您有帮助".equals(normalized)
                || "如有需要请继续提问".equals(normalized)
                || "请告诉我更多细节".equals(normalized);
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
