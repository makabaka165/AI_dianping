package com.hmdp.ai.prompt;

import com.hmdp.dto.ai.ShopAnalysisContext;
import com.hmdp.ai.intent.IntentRouteCandidate;
import com.hmdp.ai.intent.IntentSlotState;
import org.springframework.stereotype.Component;

@Component
public class PromptTemplateRegistry {

    public static final String SUMMARY_VERSION = "shop-summary-v2";
    public static final String QUALITY_SUMMARY_VERSION = "shop-quality-summary-v1";
    public static final String QA_VERSION = "shop-qa-v2";
    public static final String COMPARE_VERSION = "shop-compare-v2";
    public static final String RECOMMEND_VERSION = "shop-recommend-v2";
    public static final String INTENT_VERSION = "intent-route-v1";
    private static final int USER_TEXT_LIMIT = 1000;
    private static final int SHORT_TEXT_LIMIT = 120;
    private static final int MEMORY_LIMIT = 1200;
    private static final int BLOCK_LIMIT = 4000;

    public String summaryPrompt(ShopAnalysisContext context, String contextBlock) {
        return "请基于以下店铺评价证据生成结构化分析，只输出严格JSON，不要Markdown。\n"
                + "JSON字段必须包含 summary, sentiment, keywords, pros, cons, confidence, evidenceIds。\n"
                + "sentiment只能是positive、negative、neutral；summary为150-300字；"
                + "keywords/pros/cons均为数组；confidence为0-1小数；"
                + "evidenceIds只能引用证据中的blogId。证据不足时summary说明证据不足，confidence不超过0.4。\n\n"
                + contextBlock;
    }

    public String qualitySummaryPrompt(ShopAnalysisContext context, String contextBlock) {
        return "请基于以下高质量店铺评价证据生成结构化分析，只输出严格JSON，不要Markdown。\n"
                + "这些证据来自点赞较高且内容较完整的评价，应更重视具体体验细节，但仍不得编造未出现的信息。\n"
                + "JSON字段必须包含 summary, sentiment, keywords, pros, cons, confidence, evidenceIds。\n"
                + "sentiment只能是positive、negative、neutral；summary为150-300字；"
                + "keywords/pros/cons均为数组；confidence为0-1小数；"
                + "evidenceIds只能引用证据中的blogId。证据不足时summary说明证据不足，confidence不超过0.4。\n\n"
                + contextBlock;
    }

    public String qaPrompt(String question, String summaryMemory, String contextBlock) {
        return "用户问题：" + fenced("user_question", question, USER_TEXT_LIMIT) + "\n\n"
                + "历史店铺总结记忆：" + fenced("summary_memory", summaryMemory, MEMORY_LIMIT) + "\n\n"
                + contextBlock
                + "\n回答要求：只能基于评价证据作答；不要编造价格、地址、评分或未出现的信息；"
                + "证据不足时明确说明“当前评价证据不足以判断”。";
    }

    public String comparePrompt(String aspect, String firstContextBlock, String secondContextBlock) {
        String safeAspect = isBlank(aspect) ? "综合表现" : truncate(aspect, SHORT_TEXT_LIMIT);
        return "对比维度：" + safeAspect + "\n\n"
                + "店铺A证据：\n" + firstContextBlock
                + "\n店铺B证据：\n" + secondContextBlock
                + "\n回答要求：必须按同一维度对比，说明各自优势、短板和更适合的人群；"
                + "证据不足时不要编造结论。";
    }

    public String recommendPrompt(String userPreference, String category, Integer limit, String candidateBlock) {
        String safeCategory = isBlank(category) ? "不限" : truncate(category, SHORT_TEXT_LIMIT);
        int safeLimit = limit == null ? 5 : Math.max(1, Math.min(10, limit));
        return "用户偏好：" + fenced("user_preference", userPreference, USER_TEXT_LIMIT) + "\n"
                + "类型：" + safeCategory + "\n"
                + "推荐数量：" + safeLimit + "\n\n"
                + truncate(candidateBlock, BLOCK_LIMIT)
                + "\n回答要求：只从候选店铺中推荐；必须说明推荐理由、适合人群和不确定性；"
                + "候选不足时明确说明，不要编造不存在的店铺。";
    }

    public String freeChatPrompt(String message) {
        return "用户消息：" + fenced("user_message", message, USER_TEXT_LIMIT) + "\n\n"
                + "请作为店铺分析助手回答。若问题需要店铺ID、对比对象、推荐偏好等参数但用户未提供，"
                + "请简洁追问必要信息；不要编造店铺数据。";
    }

    public String intentClassificationPrompt(String message,
                                             IntentRouteCandidate ruleCandidate,
                                             IntentSlotState slotState) {
        return "你是店铺AI系统的意图分类器，只能输出严格JSON，不要回答用户，不要解释。\n"
                + "可选intent: SUMMARY, QA, COMPARE, RECOMMEND, FREE_CHAT, UNSUPPORTED。\n"
                + "JSON字段: intent, shopId, shopId1, shopId2, aspect, category, limit, userPreference, confidence, missingParams。\n"
                + "confidence为0到1小数；missingParams为字符串数组。\n"
                + "只做意图和参数抽取，不得编造店铺ID；用户没有提供且历史槽位也没有时，字段置null并加入missingParams。\n\n"
                + "用户消息: " + fenced("user_message", message, USER_TEXT_LIMIT) + "\n\n"
                + "规则候选: " + routeCandidateBlock(ruleCandidate) + "\n"
                + "历史槽位: " + slotStateBlock(slotState) + "\n";
    }

    private String routeCandidateBlock(IntentRouteCandidate candidate) {
        if (candidate == null) {
            return "无";
        }
        return "{intent=" + candidate.getIntent()
                + ", shopId=" + candidate.getShopId()
                + ", shopId1=" + candidate.getShopId1()
                + ", shopId2=" + candidate.getShopId2()
                + ", aspect=" + truncate(candidate.getAspect(), SHORT_TEXT_LIMIT)
                + ", category=" + truncate(candidate.getCategory(), SHORT_TEXT_LIMIT)
                + ", limit=" + candidate.getLimit()
                + ", userPreference=" + truncate(candidate.getUserPreference(), USER_TEXT_LIMIT)
                + ", confidence=" + candidate.getConfidence()
                + ", missingParams=" + candidate.safeMissingParams()
                + "}";
    }

    private String slotStateBlock(IntentSlotState slotState) {
        if (slotState == null) {
            return "无";
        }
        return "{intent=" + slotState.getIntent()
                + ", shopId=" + slotState.getShopId()
                + ", shopId1=" + slotState.getShopId1()
                + ", shopId2=" + slotState.getShopId2()
                + ", aspect=" + truncate(slotState.getAspect(), SHORT_TEXT_LIMIT)
                + ", category=" + truncate(slotState.getCategory(), SHORT_TEXT_LIMIT)
                + ", limit=" + slotState.getLimit()
                + ", userPreference=" + truncate(slotState.getUserPreference(), USER_TEXT_LIMIT)
                + "}";
    }

    private String fenced(String label, String value, int maxLength) {
        return "\n<" + label + ">\n" + truncate(value, maxLength) + "\n</" + label + ">";
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...[truncated]";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
