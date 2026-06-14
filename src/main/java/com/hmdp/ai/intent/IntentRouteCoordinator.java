package com.hmdp.ai.intent;

import com.hmdp.dto.ai.IntentRouteCandidate;
import com.hmdp.dto.ai.IntentRouteSource;
import com.hmdp.dto.ai.IntentRoutingResult;
import com.hmdp.dto.ai.IntentSlotState;
import com.hmdp.dto.ai.ShopAIIntent;
import com.hmdp.dto.ai.ShopAIRequestContext;
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
            if (llm != null && llm.getConfidence() > rule.getConfidence() && llm.getIntent() != ShopAIIntent.UNSUPPORTED) {
                selected = llm;
            }
        }

        selected = fillFromPendingOrMemory(selected, slotState);
        List<String> missing = requiredMissing(selected);
        selected.setMissingParams(missing);
        if (!missing.isEmpty()) {
            selected.setSource(IntentRouteSource.CLARIFICATION);
            selected.setClarification(clarification(selected.getIntent(), missing));
            intentSlotMemoryService.savePending(context.getUserId(), context.getSessionId(), selected);
            return selected.toRoutingResult();
        }
        intentSlotMemoryService.clearPending(context.getUserId(), context.getSessionId());
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
        Set<Long> trustedIds = trustedIds(candidate, message, explicitShopId, slotState);
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

    private Set<Long> trustedIds(IntentRouteCandidate candidate,
                                 String message,
                                 Long explicitShopId,
                                 IntentSlotState slotState) {
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
        if (hasActivePending(slotState) && selectedLooksLikeContinuation(candidate, slotState.getPendingIntent())) {
            addIfPositive(ids, slotState.getPendingShopId());
            addIfPositive(ids, slotState.getPendingShopId1());
            addIfPositive(ids, slotState.getPendingShopId2());
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

    private IntentRouteCandidate fillFromPendingOrMemory(IntentRouteCandidate selected, IntentSlotState slotState) {
        if (hasActivePending(slotState)) {
            boolean continuation = selectedLooksLikeContinuation(selected, slotState.getPendingIntent());
            boolean sameIntent = selected.getIntent() == slotState.getPendingIntent()
                    || selected.getIntent() == ShopAIIntent.UNSUPPORTED
                    || selected.getIntent() == ShopAIIntent.FREE_CHAT
                    || continuation;
            if (sameIntent) {
                if (selected.getIntent() == ShopAIIntent.UNSUPPORTED || selected.getIntent() == ShopAIIntent.FREE_CHAT
                        || continuation) {
                    selected.setIntent(slotState.getPendingIntent());
                }
                fillFromPending(selected, slotState);
                selected.setSource(IntentRouteSource.MEMORY);
                return selected;
            }
        }
        return fillFromMemory(selected, slotState);
    }

    private boolean hasActivePending(IntentSlotState slotState) {
        return slotState != null
                && slotState.getPendingIntent() != null
                && !intentSlotMemoryService.pendingExpired(slotState);
    }

    private boolean selectedLooksLikeContinuation(IntentRouteCandidate selected, ShopAIIntent pendingIntent) {
        if (selected == null || pendingIntent == null) {
            return false;
        }
        if (pendingIntent == ShopAIIntent.COMPARE) {
            return selected.getShopId() != null || selected.getShopId1() != null
                    || selected.getShopId2() != null || selected.getAspect() != null;
        }
        if (pendingIntent == ShopAIIntent.SUMMARY || pendingIntent == ShopAIIntent.QA) {
            return selected.getShopId() != null || selected.getAspect() != null;
        }
        if (pendingIntent == ShopAIIntent.RECOMMEND) {
            return selected.getUserPreference() != null || selected.getCategory() != null || selected.getLimit() != null;
        }
        return selected != null
                && selected.getShopId() == null
                && (selected.getShopId1() != null || selected.getShopId2() != null
                || selected.getAspect() != null || selected.getUserPreference() != null
                || selected.getCategory() != null || selected.getLimit() != null);
    }

    private void fillFromPending(IntentRouteCandidate selected, IntentSlotState slotState) {
        Long currentShopId = selected.getShopId();
        if (slotState.getPendingIntent() == ShopAIIntent.COMPARE && currentShopId != null) {
            if (slotState.getPendingShopId1() == null && selected.getShopId1() == null) {
                selected.setShopId1(currentShopId);
            } else if (slotState.getPendingShopId2() == null && selected.getShopId2() == null) {
                selected.setShopId2(currentShopId);
            }
        }
        if (selected.getShopId() == null) {
            selected.setShopId(slotState.getPendingShopId());
        }
        if (selected.getShopId1() == null) {
            selected.setShopId1(slotState.getPendingShopId1());
        }
        if (selected.getShopId2() == null) {
            selected.setShopId2(slotState.getPendingShopId2());
        }
        if (selected.getAspect() == null) {
            selected.setAspect(slotState.getPendingAspect());
        }
        if (selected.getUserPreference() == null) {
            selected.setUserPreference(slotState.getPendingUserPreference());
        }
        if (selected.getCategory() == null) {
            selected.setCategory(slotState.getPendingCategory());
        }
        if (selected.getLimit() == null) {
            selected.setLimit(slotState.getPendingLimit());
        }
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
        if (selected.getIntent() == ShopAIIntent.SUMMARY || selected.getIntent() == ShopAIIntent.QA) {
            if (selected.getShopId() == null && slotState.getIntent() == selected.getIntent()
                    && slotState.getShopId() != null) {
                selected.setShopId(slotState.getShopId());
                selected.setSource(IntentRouteSource.MEMORY);
            }
            return selected;
        }
        if (selected.getIntent() == ShopAIIntent.COMPARE) {
            return selected;
        }
        if (selected.getIntent() == ShopAIIntent.RECOMMEND && slotState.getIntent() == ShopAIIntent.RECOMMEND) {
            if (selected.getUserPreference() == null && slotState.getUserPreference() != null) {
                selected.setUserPreference(slotState.getUserPreference());
                selected.setSource(IntentRouteSource.MEMORY);
            }
            if (selected.getCategory() == null && slotState.getCategory() != null) {
                selected.setCategory(slotState.getCategory());
            }
            if (selected.getLimit() == null && slotState.getLimit() != null) {
                selected.setLimit(slotState.getLimit());
            }
            return selected;
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
