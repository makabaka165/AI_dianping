package com.hmdp.ai.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IntentRouter {

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    public IntentRoutingResult route(String message, Long explicitShopId) {
        String text = message == null ? "" : message.trim();
        List<Long> ids = extractNumbers(text);
        Long firstShopId = explicitShopId != null ? explicitShopId : (ids.isEmpty() ? null : ids.get(0));
        Integer limit = extractLimit(text);

        if (containsAny(text, "对比", "比较", "哪家", "哪个更", "pk", "PK")) {
            Long shopId1 = ids.size() > 0 ? ids.get(0) : null;
            Long shopId2 = ids.size() > 1 ? ids.get(1) : null;
            if (shopId1 == null || shopId2 == null) {
                return clarify(ShopAIIntent.COMPARE, "请提供需要对比的两个店铺ID。");
            }
            return IntentRoutingResult.builder()
                    .intent(ShopAIIntent.COMPARE)
                    .shopId1(shopId1)
                    .shopId2(shopId2)
                    .aspect(extractAspect(text))
                    .build();
        }

        if (containsAny(text, "推荐", "找", "适合", "附近", "想吃", "约会", "聚餐")) {
            return IntentRoutingResult.builder()
                    .intent(ShopAIIntent.RECOMMEND)
                    .userPreference(text)
                    .category(extractCategory(text))
                    .limit(limit == null ? 5 : limit)
                    .build();
        }

        if (containsAny(text, "总结", "分析", "概括", "评价怎么样")) {
            if (firstShopId == null) {
                return clarify(ShopAIIntent.SUMMARY, "请提供要分析的店铺ID。");
            }
            return IntentRoutingResult.builder()
                    .intent(ShopAIIntent.SUMMARY)
                    .shopId(firstShopId)
                    .build();
        }

        if (firstShopId != null || containsAny(text, "服务", "环境", "味道", "价格", "人均", "停车", "排队")) {
            if (firstShopId == null) {
                return clarify(ShopAIIntent.QA, "请提供要咨询的店铺ID。");
            }
            return IntentRoutingResult.builder()
                    .intent(ShopAIIntent.QA)
                    .shopId(firstShopId)
                    .build();
        }

        return IntentRoutingResult.builder()
                .intent(ShopAIIntent.FREE_CHAT)
                .build();
    }

    private IntentRoutingResult clarify(ShopAIIntent intent, String clarification) {
        return IntentRoutingResult.builder()
                .intent(intent)
                .clarification(clarification)
                .build();
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
}
