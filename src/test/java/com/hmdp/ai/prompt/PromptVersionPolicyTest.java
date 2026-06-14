package com.hmdp.ai.prompt;

import com.hmdp.dto.ai.ShopAIIntent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PromptVersionPolicyTest {

    @Test
    void shouldUseStableWhenCanaryDisabled() {
        PromptVersionPolicy policy = new PromptVersionPolicy();
        ReflectionTestUtils.setField(policy, "canaryEnabled", false);
        ReflectionTestUtils.setField(policy, "canaryRatio", 100.0);

        PromptTemplateRender render = policy.render(ShopAIIntent.QA, "shop-qa-v3", "shop-qa-v4",
                "10001", "qa:1", "prompt");

        assertThat(render.getVersion()).isEqualTo("shop-qa-v3");
        assertThat(render.getVariant()).isEqualTo("stable");
    }

    @Test
    void shouldUseCanaryWhenRatioIsFull() {
        PromptVersionPolicy policy = new PromptVersionPolicy();
        ReflectionTestUtils.setField(policy, "canaryEnabled", true);
        ReflectionTestUtils.setField(policy, "canaryRatio", 100.0);

        PromptTemplateRender render = policy.render(ShopAIIntent.QA, "shop-qa-v3", "shop-qa-v4",
                "10001", "qa:1", "prompt");

        assertThat(render.getVersion()).isEqualTo("shop-qa-v4");
        assertThat(render.getVariant()).isEqualTo("canary");
    }

    @Test
    void shouldKeepSameUserRouteStable() {
        PromptVersionPolicy policy = new PromptVersionPolicy();
        ReflectionTestUtils.setField(policy, "canaryEnabled", true);
        ReflectionTestUtils.setField(policy, "canaryRatio", 25.0);

        boolean first = policy.shouldUseCanary(ShopAIIntent.RECOMMEND, "u1", "recommend:date");
        boolean second = policy.shouldUseCanary(ShopAIIntent.RECOMMEND, "u1", "recommend:date");

        assertThat(second).isEqualTo(first);
    }
}
