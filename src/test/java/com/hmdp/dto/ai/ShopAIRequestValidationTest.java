package com.hmdp.dto.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import javax.validation.ConstraintViolation;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShopAIRequestValidationTest {

    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void compareRequestShouldRejectLongAspectAndSessionId() {
        ShopCompareRequest request = new ShopCompareRequest();
        request.setShopId1(1L);
        request.setShopId2(2L);
        request.setAspect(repeat("a", 101));
        request.setSessionId(repeat("s", 65));

        Set<ConstraintViolation<ShopCompareRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("aspect", "sessionId");
    }

    @Test
    void recommendRequestShouldRejectLongCategoryAndSessionId() {
        ShopRecommendRequest request = new ShopRecommendRequest();
        request.setUserPreference("quiet dinner");
        request.setCategory(repeat("c", 51));
        request.setSessionId(repeat("s", 65));

        Set<ConstraintViolation<ShopRecommendRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("category", "sessionId");
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
