package com.hmdp.ai.intent;

import com.hmdp.dto.ai.IntentRouteCandidate;
import com.hmdp.dto.ai.ShopAIIntent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleIntentParserTest {

    private final RuleIntentParser parser = new RuleIntentParser();

    @Test
    void shouldParseSummaryIntentWithShopId() {
        IntentRouteCandidate result = parser.parse("帮我总结分析店铺12", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.SUMMARY);
        assertThat(result.getShopId()).isEqualTo(12L);
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void shouldParseCompareIntentWithTwoShopIdsAndAspect() {
        IntentRouteCandidate result = parser.parse("对比店铺1和店铺2的服务", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.COMPARE);
        assertThat(result.getShopId1()).isEqualTo(1L);
        assertThat(result.getShopId2()).isEqualTo(2L);
        assertThat(result.getAspect()).isEqualTo("服务");
    }

    @Test
    void shouldParseRecommendIntentWithLimit() {
        IntentRouteCandidate result = parser.parse("推荐3家适合约会的餐厅", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.RECOMMEND);
        assertThat(result.getLimit()).isEqualTo(3);
        assertThat(result.getCategory()).isEqualTo("餐厅");
    }

    @Test
    void shouldPreferShopQaWhenFindMessageContainsExplicitShopId() {
        IntentRouteCandidate result = parser.parse("帮我找一下店铺12的评价", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.QA);
        assertThat(result.getShopId()).isEqualTo(12L);
    }

    @Test
    void shouldRecommendWhenFindMessageHasNoShopId() {
        IntentRouteCandidate result = parser.parse("找服务好的餐厅", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.RECOMMEND);
        assertThat(result.getUserPreference()).isEqualTo("找服务好的餐厅");
    }

    @Test
    void shouldReturnMissingParamWhenShopIdMissing() {
        IntentRouteCandidate result = parser.parse("这家店服务怎么样", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.QA);
        assertThat(result.safeMissingParams()).contains("shopId");
        assertThat(result.getConfidence()).isLessThan(0.85);
    }
}
