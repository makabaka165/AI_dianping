package com.hmdp.dto.ai;

import org.junit.jupiter.api.Test;

import javax.validation.Validation;
import javax.validation.Validator;

import static org.assertj.core.api.Assertions.assertThat;

class ShopAIRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void chatSessionIdShouldRejectColonAndLongValue() {
        ShopChatRequest colon = new ShopChatRequest();
        colon.setSessionId("a:b");
        colon.setMessage("hello");
        assertThat(validator.validate(colon)).isNotEmpty();

        ShopChatRequest tooLong = new ShopChatRequest();
        tooLong.setSessionId("a".repeat(65));
        tooLong.setMessage("hello");
        assertThat(validator.validate(tooLong)).isNotEmpty();
    }

    @Test
    void askSessionIdShouldRejectColonAndLongValue() {
        ShopAskRequest colon = new ShopAskRequest();
        colon.setSessionId("a:b");
        colon.setQuestion("服务怎么样");
        assertThat(validator.validate(colon)).isNotEmpty();

        ShopAskRequest tooLong = new ShopAskRequest();
        tooLong.setSessionId("a".repeat(65));
        tooLong.setQuestion("服务怎么样");
        assertThat(validator.validate(tooLong)).isNotEmpty();
    }

    @Test
    void compareAndRecommendShouldAcceptSafeSessionId() {
        ShopCompareRequest compare = new ShopCompareRequest();
        compare.setSessionId("safe_session-1.2");
        assertThat(validator.validate(compare)).isEmpty();

        ShopRecommendRequest recommend = new ShopRecommendRequest();
        recommend.setSessionId("safe_session-1.2");
        recommend.setUserPreference("约会餐厅");
        assertThat(validator.validate(recommend)).isEmpty();
    }
}
