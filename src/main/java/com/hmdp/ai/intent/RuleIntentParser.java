package com.hmdp.ai.intent;

import com.hmdp.dto.ai.IntentRouteCandidate;
import com.hmdp.dto.ai.IntentRouteSource;
import com.hmdp.dto.ai.ShopAIIntent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleIntentParser {

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern SHOP_ID_PREFIX = Pattern.compile("(?i)(?:shopId|shop|id|ID|\\u5e97\\u94fa|\\u95e8\\u5e97|\\u5546\\u5bb6|\\u5e97)[^0-9]{0,6}(\\d+)");
    private static final Pattern SHOP_ID_SUFFIX = Pattern.compile("(\\d+)\\s*(?:\\u53f7\\u5e97|\\u5e97)");

    public IntentRouteCandidate parse(String message, Long explicitShopId) {
        String text = message == null ? "" : message.trim();
        List<Long> shopIds = extractShopIds(text);
        Long firstShopId = explicitShopId != null ? explicitShopId : (shopIds.isEmpty() ? null : shopIds.get(0));
        boolean hasShopReference = firstShopId != null;
        Integer limit = extractLimit(text);

        if (containsAny(text, "对比", "比较", "哪家", "哪个更", "pk", "PK")) {
            List<Long> compareIds = shopIds.isEmpty() ? extractNumbers(text) : shopIds;
            Long shopId1 = compareIds.size() > 0 ? compareIds.get(0) : null;
            Long shopId2 = compareIds.size() > 1 ? compareIds.get(1) : null;
            List<String> missing = new ArrayList<>();
            if (shopId1 == null) {
                missing.add("shopId1");
            }
            if (shopId2 == null) {
                missing.add("shopId2");
            }
            return candidate(ShopAIIntent.COMPARE, missing.isEmpty() ? 0.95 : 0.55)
                    .shopId1(shopId1)
                    .shopId2(shopId2)
                    .aspect(extractAspect(text))
                    .missingParams(missing)
                    .clarification(missing.isEmpty() ? null : "请提供需要对比的两个店铺ID。")
                    .build();
        }

        if (!hasShopReference && containsRecommendSignal(text)) {
            return candidate(ShopAIIntent.RECOMMEND, 0.85)
                    .userPreference(text)
                    .category(extractCategory(text))
                    .limit(limit == null ? 5 : limit)
                    .missingParams(Collections.emptyList())
                    .build();
        }

        if (containsAny(text, "总结", "分析", "概括", "评价怎么样")) {
            List<String> missing = firstShopId == null
                    ? Collections.singletonList("shopId")
                    : Collections.emptyList();
            return candidate(ShopAIIntent.SUMMARY, firstShopId == null ? 0.55 : 0.9)
                    .shopId(firstShopId)
                    .missingParams(missing)
                    .clarification(firstShopId == null ? "请提供要分析的店铺ID。" : null)
                    .build();
        }

        if (hasShopReference || containsAny(text, "服务", "环境", "味道", "价格", "人均", "停车", "排队")) {
            List<String> missing = firstShopId == null
                    ? Collections.singletonList("shopId")
                    : Collections.emptyList();
            return candidate(ShopAIIntent.QA, firstShopId == null ? 0.55 : 0.8)
                    .shopId(firstShopId)
                    .aspect(extractAspect(text))
                    .missingParams(missing)
                    .clarification(firstShopId == null ? "请提供要咨询的店铺ID。" : null)
                    .build();
        }

        return candidate(ShopAIIntent.FREE_CHAT, 0.3)
                .missingParams(Collections.emptyList())
                .build();
    }

    private IntentRouteCandidate.IntentRouteCandidateBuilder candidate(ShopAIIntent intent, double confidence) {
        return IntentRouteCandidate.builder()
                .intent(intent)
                .confidence(confidence)
                .source(IntentRouteSource.RULE);
    }

    private List<Long> extractNumbers(String text) {
        List<Long> numbers = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            try {
                numbers.add(Long.parseLong(matcher.group()));
            } catch (NumberFormatException ignored) {
                // ignore invalid number
            }
        }
        return numbers;
    }

    private List<Long> extractShopIds(String text) {
        List<Long> ids = new ArrayList<>();
        addMatchedIds(ids, SHOP_ID_PREFIX.matcher(text));
        addMatchedIds(ids, SHOP_ID_SUFFIX.matcher(text));
        return ids;
    }

    private void addMatchedIds(List<Long> target, Matcher matcher) {
        while (matcher.find()) {
            try {
                Long value = Long.parseLong(matcher.group(1));
                if (value > 0 && !target.contains(value)) {
                    target.add(value);
                }
            } catch (NumberFormatException ignored) {
                // ignore invalid number
            }
        }
    }

    private Integer extractLimit(String text) {
        Matcher matcher = Pattern.compile("(推荐|找|给我)(\\d{1,2})家").matcher(text);
        if (matcher.find()) {
            return Math.max(1, Math.min(10, Integer.parseInt(matcher.group(2))));
        }
        return null;
    }

    private String extractAspect(String text) {
        String[] aspects = {"服务", "环境", "味道", "价格", "性价比", "位置", "停车", "排队", "卫生"};
        for (String aspect : aspects) {
            if (text.contains(aspect)) {
                return aspect;
            }
        }
        return null;
    }

    private String extractCategory(String text) {
        String[] categories = {"餐厅", "咖啡", "火锅", "烧烤", "面", "甜品", "奶茶", "酒吧"};
        for (String category : categories) {
            if (text.contains(category)) {
                return category;
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsRecommendSignal(String text) {
        return containsAny(text, "推荐", "找", "适合", "附近", "想吃", "约会", "聚餐");
    }
}
