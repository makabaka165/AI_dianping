package com.hmdp.ai.intent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterTest {

    private final IntentRouter intentRouter = new IntentRouter();

    @Test
    void shouldRouteSummaryIntentWithShopId() {
        IntentRoutingResult result = intentRouter.route("帮我总结分析店铺12", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.SUMMARY);
        assertThat(result.getShopId()).isEqualTo(12L);
    }

    @Test
    void shouldRouteCompareIntentWithTwoShopIdsAndAspect() {
        IntentRoutingResult result = intentRouter.route("对比店铺1和店铺2的服务", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.COMPARE);
        assertThat(result.getShopId1()).isEqualTo(1L);
        assertThat(result.getShopId2()).isEqualTo(2L);
        assertThat(result.getAspect()).isEqualTo("服务");
    }

    @Test
    void shouldRouteRecommendIntentWithLimit() {
        IntentRoutingResult result = intentRouter.route("推荐3家适合约会的餐厅", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.RECOMMEND);
        assertThat(result.getLimit()).isEqualTo(3);
        assertThat(result.getCategory()).isEqualTo("餐厅");
    }

    @Test
    void shouldClarifyWhenShopIdMissing() {
        IntentRoutingResult result = intentRouter.route("这家店服务怎么样", null);

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.QA);
        assertThat(result.getClarification()).contains("店铺ID");
    }
}
