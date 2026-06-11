package com.hmdp.ai.prompt;

import com.hmdp.dto.ai.ShopAnalysisContext;
import org.springframework.stereotype.Component;

@Component
public class PromptTemplateRegistry {

    public static final String SUMMARY_VERSION = "shop-summary-v2";
    public static final String QA_VERSION = "shop-qa-v2";
    public static final String COMPARE_VERSION = "shop-compare-v2";
    public static final String RECOMMEND_VERSION = "shop-recommend-v2";
    public static final String INTENT_VERSION = "intent-route-v1";

    public String summaryPrompt(ShopAnalysisContext context, String contextBlock) {
        return "请基于以下店铺评价证据生成结构化分析，只输出严格JSON，不要Markdown。\n"
                + "JSON字段必须包含 summary, sentiment, keywords, pros, cons, confidence, evidenceIds。\n"
                + "sentiment只能是positive、negative、neutral；summary为150-300字；"
                + "keywords/pros/cons均为数组；confidence为0-1小数；"
                + "evidenceIds只能引用证据中的blogId。证据不足时summary说明证据不足，confidence不超过0.4。\n\n"
                + contextBlock;
    }

    public String qaPrompt(String question, String summaryMemory, String contextBlock) {
        return "用户问题：" + question + "\n\n"
                + "历史店铺总结记忆：" + summaryMemory + "\n\n"
                + contextBlock
                + "\n回答要求：只能基于评价证据作答；不要编造价格、地址、评分或未出现的信息；"
                + "证据不足时明确说明“当前评价证据不足以判断”。";
    }

    public String comparePrompt(String aspect, String firstContextBlock, String secondContextBlock) {
        String safeAspect = isBlank(aspect) ? "综合表现" : aspect.trim();
        return "对比维度：" + safeAspect + "\n\n"
                + "店铺A证据：\n" + firstContextBlock
                + "\n店铺B证据：\n" + secondContextBlock
                + "\n回答要求：必须按同一维度对比，说明各自优势、短板和更适合的人群；"
                + "证据不足时不要编造结论。";
    }

    public String recommendPrompt(String userPreference, String category, Integer limit, String candidateBlock) {
        String safeCategory = isBlank(category) ? "不限" : category.trim();
        int safeLimit = limit == null ? 5 : Math.max(1, Math.min(10, limit));
        return "用户偏好：" + userPreference + "\n"
                + "类型：" + safeCategory + "\n"
                + "推荐数量：" + safeLimit + "\n\n"
                + candidateBlock
                + "\n回答要求：只从候选店铺中推荐；必须说明推荐理由、适合人群和不确定性；"
                + "候选不足时明确说明，不要编造不存在的店铺。";
    }

    public String freeChatPrompt(String message) {
        return "用户消息：" + message + "\n\n"
                + "请作为店铺分析助手回答。若问题需要店铺ID、对比对象、推荐偏好等参数但用户未提供，"
                + "请简洁追问必要信息；不要编造店铺数据。";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
