package com.hmdp.ai.intent;

import com.hmdp.ai.orchestration.ShopAIRequestContext;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IntentRouteCoordinator {

    private static final double RULE_DIRECT_THRESHOLD = 0.85;
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern SHOP_ID_PREFIX = Pattern.compile("(?i)(?:shopId|shop|id|ID|\\u5e97\\u94fa|\\u95e8\\u5e97|\\u5546\\u5bb6|\\u5e97)[^0-9]{0,6}(\\d+)");
    private static final Pattern SHOP_ID_SUFFIX = Pattern.compile("(\\d+)\\s*(?:\\u53f7\\u5e97|\\u5e97)");
    private static final Pattern COMPARE_HINT = Pattern.compile("(?i)(\\u5bf9\\u6bd4|\\u6bd4\\u8f83|pk)");

    @Resource
    private RuleIntentParser ruleIntentParser;

    @Resource
    private LLMIntentClassifier llmIntentClassifier;

    @Resource
    private IntentSlotMemoryService intentSlotMemoryService;

    public IntentRoutingResult route(ShopAIRequestContext context, String message, Long explicitShopId) {
        IntentRouteCandidate rule = ruleIntentParser.parse(message, explicitShopId);
        IntentSlotState slotState = intentSlotMemoryService.load(context.getUserId(), context.getSessionId());
        IntentRouteCandidate selected = rule;
        if (rule.getConfidence() < RULE_DIRECT_THRESHOLD) {
            IntentRouteCandidate llm = llmIntentClassifier.classify(message, rule, slotState);
            sanitizeLlmIds(llm, message, explicitShopId, slotState);
            if (llm.getConfidence() > rule.getConfidence() && llm.getIntent() != ShopAIIntent.UNSUPPORTED) {
                selected = llm;
            }
        }

        selected = fillFromMemory(selected, slotState);
        List<String> missing = requiredMissing(selected);
        selected.setMissingParams(missing);
        if (!missing.isEmpty()) {
            selected.setSource(IntentRouteSource.CLARIFICATION);
            selected.setClarification(clarification(selected.getIntent(), missing));
            return selected.toRoutingResult();
        }
        intentSlotMemoryService.save(context.getUserId(), context.getSessionId(), selected);
        return selected.toRoutingResult();
    }

    private void sanitizeLlmIds(IntentRouteCandidate candidate,
                                String message,
                                Long explicitShopId,
                                IntentSlotState slotState) {
        if (candidate == null) {
            return;
        }
        Set<Long> trustedIds = trustedIds(message, explicitShopId, slotState);
        if (!isTrusted(candidate.getShopId(), trustedIds)) {
            candidate.setShopId(null);
        }
        if (!isTrusted(candidate.getShopId1(), trustedIds)) {
            candidate.setShopId1(null);
        }
        if (!isTrusted(candidate.getShopId2(), trustedIds)) {
            candidate.setShopId2(null);
        }
    }

    private Set<Long> trustedIds(String message, Long explicitShopId, IntentSlotState slotState) {
        Set<Long> ids = new HashSet<>();
        if (explicitShopId != null && explicitShopId > 0) {
            ids.add(explicitShopId);
        }
        String text = message == null ? "" : message;
        if (COMPARE_HINT.matcher(text).find()) {
            addAllNumbers(ids, NUMBER.matcher(text));
        } else {
            addCapturedNumbers(ids, SHOP_ID_PREFIX.matcher(text));
            addCapturedNumbers(ids, SHOP_ID_SUFFIX.matcher(text));
        }
        if (slotState != null) {
            addIfPositive(ids, slotState.getShopId());
            addIfPositive(ids, slotState.getShopId1());
            addIfPositive(ids, slotState.getShopId2());
        }
        return ids;
    }

    private void addAllNumbers(Set<Long> ids, Matcher matcher) {
        while (matcher.find()) {
            addParsed(ids, matcher.group());
        }
    }

    private void addCapturedNumbers(Set<Long> ids, Matcher matcher) {
        while (matcher.find()) {
            addParsed(ids, matcher.group(1));
        }
    }

    private void addParsed(Set<Long> ids, String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value > 0) {
                ids.add(value);
            }
        } catch (NumberFormatException ignored) {
            // ignore invalid number
        }
    }

    private void addIfPositive(Set<Long> ids, Long value) {
        if (value != null && value > 0) {
            ids.add(value);
        }
    }

    private boolean isTrusted(Long value, Set<Long> trustedIds) {
        return value == null || trustedIds.contains(value);
    }

    private IntentRouteCandidate fillFromMemory(IntentRouteCandidate selected, IntentSlotState slotState) {
        if (slotState == null) {
            return selected;
        }
        boolean currentHasAspectOnly = selected.getShopId() == null
                && selected.getShopId1() == null
                && selected.getShopId2() == null
                && selected.getAspect() != null;
        if (currentHasAspectOnly && slotState.getIntent() == ShopAIIntent.COMPARE
                && slotState.getShopId1() != null && slotState.getShopId2() != null) {
            selected.setIntent(ShopAIIntent.COMPARE);
            selected.setShopId1(slotState.getShopId1());
            selected.setShopId2(slotState.getShopId2());
            selected.setSource(IntentRouteSource.MEMORY);
            return selected;
        }
        if (selected.getShopId() == null && slotState.getShopId() != null) {
            selected.setShopId(slotState.getShopId());
            selected.setSource(IntentRouteSource.MEMORY);
        }
        if (selected.getShopId1() == null && slotState.getShopId1() != null) {
            selected.setShopId1(slotState.getShopId1());
            selected.setSource(IntentRouteSource.MEMORY);
        }
        if (selected.getShopId2() == null && slotState.getShopId2() != null) {
            selected.setShopId2(slotState.getShopId2());
            selected.setSource(IntentRouteSource.MEMORY);
        }
        if (selected.getAspect() == null && slotState.getAspect() != null) {
            selected.setAspect(slotState.getAspect());
        }
        if (selected.getCategory() == null && slotState.getCategory() != null) {
            selected.setCategory(slotState.getCategory());
        }
        return selected;
    }

    private List<String> requiredMissing(IntentRouteCandidate candidate) {
        List<String> missing = new ArrayList<>();
        ShopAIIntent intent = candidate.getIntent();
        if (intent == ShopAIIntent.SUMMARY || intent == ShopAIIntent.QA) {
            if (candidate.getShopId() == null) {
                missing.add("shopId");
            }
        } else if (intent == ShopAIIntent.COMPARE) {
            if (candidate.getShopId1() == null) {
                missing.add("shopId1");
            }
            if (candidate.getShopId2() == null) {
                missing.add("shopId2");
            }
        } else if (intent == ShopAIIntent.RECOMMEND) {
            if (isBlank(candidate.getUserPreference())) {
                missing.add("userPreference");
            }
        }
        return missing;
    }

    private String clarification(ShopAIIntent intent, List<String> missing) {
        if (intent == ShopAIIntent.COMPARE) {
            return "请提供需要对比的两个店铺ID。";
        }
        if (intent == ShopAIIntent.SUMMARY || intent == ShopAIIntent.QA) {
            return "请提供要分析或咨询的店铺ID。";
        }
        if (intent == ShopAIIntent.RECOMMEND) {
            return "请补充你的推荐偏好。";
        }
        return "请补充店铺ID、对比对象或推荐偏好。";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
