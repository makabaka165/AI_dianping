package com.hmdp.ai.model;

import com.hmdp.ai.intent.IntentRouteCandidate;
import com.hmdp.ai.intent.ShopAIIntent;
import com.hmdp.service.ai.ShopAIService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelGatewayTest {

    @Mock
    private ShopAIService shopAIService;

    private ModelGateway modelGateway;

    @BeforeEach
    void setUp() {
        modelGateway = new ModelGateway();
        ReflectionTestUtils.setField(modelGateway, "shopAIService", shopAIService);
        ReflectionTestUtils.setField(modelGateway, "timeoutSeconds", 30L);
        ReflectionTestUtils.setField(modelGateway, "maxConcurrentCalls", 8);
        ReflectionTestUtils.setField(modelGateway, "rateLimitPeriodSeconds", 1L);
        ReflectionTestUtils.setField(modelGateway, "rateLimitPermits", 100);
    }

    @Test
    void shouldParseLowercaseIntentAndSingleMissingParam() throws Exception {
        when(shopAIService.classifyIntent("prompt")).thenReturn(
                "{\"intent\":\"qa\",\"confidence\":0.88,\"missingParams\":\"shopId\"}");

        IntentRouteCandidate result = modelGateway.classifyIntent("prompt");

        assertThat(result.getIntent()).isEqualTo(ShopAIIntent.QA);
        assertThat(result.safeMissingParams()).containsExactly("shopId");
        assertThat(result.getConfidence()).isEqualTo(0.88);
    }

    @Test
    void shouldTimeoutSlowModelCall() {
        ReflectionTestUtils.setField(modelGateway, "timeoutSeconds", 1L);
        when(shopAIService.analyzeShopData("m1", "prompt")).thenAnswer(invocation -> {
            Thread.sleep(1500);
            return "late";
        });

        assertThatThrownBy(() -> modelGateway.generateAnswer("m1", "prompt"))
                .isInstanceOf(Exception.class);
    }
}
